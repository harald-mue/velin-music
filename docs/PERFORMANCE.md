# Android library performance plan

## Problem statement

The pre-remediation client was functionally bounded but visibly request-driven. With a real library of 2,096 tracks, users encountered manual **Load more** controls, cold album/artist detail requests, and delayed artwork while scrolling. This is not a SQLite scale limit. It is a client loading, caching, and image-delivery problem.

Velin still targets libraries of at least 100,000 tracks. Loading the complete library into memory at startup is therefore not an acceptable fix.

## Observed baseline

A physical Android device (`1080 × 2392`) was inspected with a 2,096-track remote library:

- application PSS was approximately 288 MiB;
- decoded bitmap allocations were approximately 15 MiB;
- Coil's disk cache was approximately 2.8 MiB at the time of inspection;
- the Home screen had accumulated `100+` artists, `150+` albums, and `50+` tracks through manual pagination;
- visible artwork eventually rendered, but each first-seen cover used the original cached artwork response;
- no Velin-specific main-thread stall was present in the sampled log output.

These numbers do not indicate that 2,096 metadata rows exhaust device memory. The interaction delays are dominated by network round trips, manual page boundaries, repeated cold detail loads, and oversized artwork transfers.

A later debug timing run identified an additional server bottleneck: status returned in about one second while summary and album-page requests timed out after 15 seconds during library activity. The server's single SQLite connection serialized API reads behind scan writes despite WAL mode. The pool now permits four connections, with foreign-key, WAL, and busy-timeout PRAGMAs applied to every connection through the driver DSN.

A follow-up device run showed successful album pagination in 911 ms but bursts of HTTP/2 stream resets while rapidly replacing visible artwork requests. A second run proved those bursts could starve album pagination until its 15-second timeout. Android now isolates artwork on at most two HTTP/1.1 connections, avoiding HTTP/2 reset amplification while preserving HTTP/2 for metadata. Coil cancellations for rows leaving the viewport are not retried. Artwork-client disposal cancels calls without synchronously evicting TLS connections on the main thread. Uncached server thumbnails use a faster approximate bilinear scaler; immutable derivative and client caches retain the result.

## Measured baseline bottlenecks

### Manual pagination

The Android API defaults to 50 items. `LibraryScreen` adds an explicit **Load more** row. This exposes transport pagination directly to the user and makes normal browsing feel incomplete.

### Eager startup work with incomplete results

Startup fetches the status plus the first artist, album, and track pages concurrently. Home then displays loaded item counts (`50+`, `100+`) rather than exact library totals. It downloads track rows even though Home primarily needs totals and a small album shelf.

### Cold detail screens

Opening an album requests all album tracks in pages of up to 200. A normal album is one request, but the client clears the previous detail state and has no album-track cache. Reopening the same album repeats the request. Artist detail currently loads the artist record and then all artist tracks serially.

### Full-size artwork delivery

The server stores and serves validated original embedded artwork up to 8 MiB and 50 million pixels. Android decodes to display constraints, but it must still transfer the original bytes for a small row or grid thumbnail. Coil caching helps repeat views but cannot make the first transfer small. A content-hash cover ID is stable, yet the current response cache lifetime is only one hour.

### Growing in-memory accumulated lists

`AccumulatedPage.append` copies growing lists. Compose lazy containers render only visible rows, but retaining and repeatedly copying a very large catalog is not the long-term 100,000-track design.

## Performance goals

Measure on a physical phone through both LAN and the normal HTTPS route.

- Cached application shell and cached lists visible in under 300 ms.
- Cold Home content usable after one summary request and one album request.
- No manual **Load more** action during ordinary scrolling.
- Cold album detail displays its header immediately and its complete normal track list in one network round trip.
- Reopening a recently viewed album or artist displays cached tracks immediately.
- First visible thumbnail is transferred as an appropriately sized derivative, not a multi-megabyte original.
- Pagination and memory remain bounded for 100,000-track libraries.
- Playback startup and Media3 queue behavior remain independent from catalog prefetching.

## Phase 0 — measurement and regression fixtures

1. Add debug-only request timing for endpoint category, status, response bytes, and duration. Never log authorization headers, tokens, pairing codes, URLs with credentials, or filesystem paths.
2. Record cold/warm Home, album-open, artist-open, search, and first-cover timings.
3. Add generated server benchmarks/fixtures for approximately 2,000 and 100,000 tracks.
4. Record original cover byte-size and dimension distributions from a test library without exposing source paths.

This phase defines whether delays are metadata, image transfer, decoding, or proxy latency instead of guessing.

## Phase 1 — remove user-visible pagination

**Implemented 2026-09-05.** This phase does not require local database synchronization.

1. Request up to 200 albums and 200 artists for their initial pages; use a smaller initial track/search page where rows are heavier.
2. Trigger the next cursor automatically when the user approaches the last 10–20 visible items.
3. Replace **Load more** with an inline loading row and a retry action only after an error.
4. Prevent duplicate requests per section/cursor and discard stale completions after refresh or cursor replacement.
5. Preserve already loaded items while background refresh runs.
6. Load artist metadata and artist tracks concurrently.
7. Add bounded in-memory LRU caches for album tracks, artist details/tracks, and track details. Bound by both entry count and total retained tracks.
8. Keep album headers visible while tracks load; use skeleton/progress rows rather than an empty screen.

The API remains cursor-paginated and capped at 200. Automatic pagination improves interaction without weakening server bounds.

## Phase 2 — correctly sized artwork

**Implemented 2026-09-05.** This is expected to provide the largest first-scroll improvement.

1. Add authenticated derivative endpoints with whitelisted path sizes, for example:
   - `/api/v1/covers/{id}/128`
   - `/api/v1/covers/{id}/256`
   - `/api/v1/covers/{id}/512`
   - existing `/api/v1/covers/{id}` for the original
2. Generate derivatives into a content-addressed managed cache without modifying music files.
3. Bound input bytes, decoded dimensions, output dimensions, concurrent generation, and generated-cache size. Validate the managed path, regular-file type, encoded format, byte size, and dimensions whenever a cached derivative is opened.
4. Use immutable private caching because the cover ID is a content hash (`private, max-age=31536000, immutable`).
5. Request 256 px for rows and standard surfaces and 512 px for detail/Now Playing; 128 px remains available for future compact surfaces.
6. Configure Coil to use at most 15% of the application memory class and a 64 MiB private disk cache. Derivative URLs provide stable size-specific keys.
7. Serialize derivative generation and recheck the cache after waiting, coalescing simultaneous requests without unbounded decoder concurrency.

Do not put bearer tokens or credentials into image URLs or cache keys.

## Phase 3 — exact summary and lazy section loading

**Implemented 2026-09-06.**

1. Add a small authenticated library summary endpoint with exact artist, album, and track counts plus a library revision.
2. Home loads summary plus only the album shelf it renders.
3. Artists and Tracks load on first navigation to those sections, not at every application start.
4. Use the revision to invalidate section and detail caches after indexed track changes.

This removes unnecessary startup payload and replaces misleading `50+` counters with real totals.

## Phase 4 — persistent metadata snapshot

**Implemented 2026-09-06.** The implementation uses Room and Paging 3 without weakening the server's bounded cursor API.

1. Public artist, album, and track metadata is stored in an app-private Room database. Tokens, source paths, and credential-bearing URLs are not stored there.
2. Each cache is namespaced by SHA-256 of the normalized server URL, a NUL separator, and the device ID. The bearer token is deliberately excluded. Disconnect deletes the current namespace.
3. A refresh downloads complete artist, album, and track collections in 200-item server pages into a new staging generation.
4. The summary revision is read before and after the download. Activation requires an unchanged revision and exact downloaded artist, album, and track counts matching the first summary.
5. The new generation becomes visible through one transactional `cache_state` update; incomplete generations are deleted and the previous active snapshot remains available.
6. Room supplies `PagingSource` instances with a page size of 50 for the three library lists. Home reads bounded recently added and discovery selections, and album detail reads cached tracks when an active snapshot contains that album. Discovery starts at a random per-process opaque ID and wraps through two index-bounded ranges, avoiding both a linear offset scan and `ORDER BY RANDOM()` over the catalog.
7. Only the empty-cache bootstrap requests a network summary and bounded album shelf while the full snapshot is built. Search, artist detail, track detail, and Android Auto remain network-backed.
8. Snapshot requests retry only transport failures and HTTP 408, 429, 500, 502, 503, and 504, for at most three attempts total.

Room schema version 2 is exported into the repository; migration 1→2 retains the active generation and forces one refresh to populate album recency. A delta/tombstone protocol and local FTS are not part of this implementation.

Physical-device validation against the 2,096-track library activated a complete snapshot containing 176 artists, 253 albums, and 2,096 tracks. The measured synchronization took about 18 seconds from the first status request through the final summary verification. A subsequent cold process start reported 1.056 seconds of Android launch time, rendered the cached counts and Home shelf, and used only status plus summary requests; matching revision and counts avoided another catalog download.

## Server query follow-up

Before 100,000-track validation:

- benchmark artist count queries, which currently use correlated subqueries;
- add indexes for `tracks.album_artist_id` and `albums.album_artist_id` if query plans require them;
- benchmark album grouping and keyset cursors;
- keep the bounded four-connection WAL pool and globally serialized scan invariant unless measurements justify another reviewed change;
- consider JSON compression for LAN clients only after response-size measurements.

## Rollout order

1. Phase 0 instrumentation and baseline.
2. Phase 1 automatic pagination, parallel detail requests, and bounded memory caches.
3. Phase 2 artwork derivatives and explicit Coil caching.
4. Phase 3 exact summary, revision invalidation, and lazy section loading.
5. Phase 4 revision/count-verified Room snapshots and local PagingSource rendering.
6. Re-measure on the 2,096-track device and a generated 100,000-track fixture.

Each phase must retain authenticated requests, token-free URLs/metadata, queue caps, cancellation, latest-request-wins behavior, and Android Auto's shared Media3 session.
