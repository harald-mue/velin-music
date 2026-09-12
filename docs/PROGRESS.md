# Velin Development Progress

Last updated: 2026-09-12

## Current status

The Go executable provides storage, indexing, authentication, administration, protected library APIs, artwork, and original-format streaming. The native Android project builds with Kotlin, Compose, and API 37 and provides a graphite visual system with Home/Queue/Library navigation, in-library search, and adaptive portrait/landscape layouts.

Android has exact summary counts, a revision/count-verified Room snapshot cache, PagingSource-backed library lists, bounded **Recently added** and **Discover** Home sections, and cached album detail when active. Search, artist detail, and track detail remain network-backed. Android Auto Albums and Artists read the active Room snapshot (list for Albums; network only if the snapshot has no rows); Auto search stays network-backed. Auto root **Recent** and Discover read the Room home shelves without library counts and stay visible as separate tabs even when they share albums. Opening a cached album or artist in Auto uses Room tracks. Browse grids embed authenticated covers. Bounded visible-result/album queues, album/artist add-to-queue, queue reordering, a device-local saved-queue slot, and track-detail enqueue actions are wired through Media3, including authenticated artwork, Now Playing, seeking, automatic advance, previous/next, queue inspection/removal, shuffle, and repeat. Playback is owned by an exported `MediaLibraryService` that exposes the Android Auto media library (home shelves plus Albums and Artists). The server prewarms bounded 256/512 px artwork variants in one low-pressure worker.

## Current milestone

**M10 — Playback** (active; authenticated queues, artwork, mini-player, Now Playing, seeking, previous/next, queue inspection/removal/reordering, local Save/Load, shuffle, repeat, enqueue, Android Auto media-library browsing, and physical-device/in-car validation are complete; local/Media3 paused playback restoration is implemented and paired-emulator process-death/reboot/Auto resumption is measured, while disconnect/re-pair and the signed physical-phone matrix remain).

## Completed

- [x] Monorepo layout, English documentation, and persistent agent instructions.
- [x] Go module, development commands, environment configuration, and minimal server executable.
- [x] Private data-directory creation with permission and symlink checks.
- [x] Structured JSON lifecycle logging and graceful HTTP shutdown.
- [x] Pure-Go SQLite setup with WAL, foreign keys, busy timeout, and a bounded four-connection pool configured consistently through the driver DSN.
- [x] Ordered embedded migrations through `005_admin_auth.sql`.
- [x] `GET /api/v1/status` with automated tests.
- [x] Canonical SQLite-backed library-root management.
- [x] Callback-based FLAC/MP3 discovery with case-insensitive extensions and symlink skipping.
- [x] Bounded FLAC/MP3 tags, technical metadata, persisted text fields, and embedded-artwork extraction without audio-frame decoding.
- [x] Media identity revalidation between discovery and parsing.
- [x] Validated JPEG/PNG/GIF/WebP artwork, SHA-256 cache storage, existing-entry verification, and cover deduplication.
- [x] Identity-based skipping of unchanged files, transactional track/FTS upserts, durable scan markers, and complete-scan-only deletion.
- [x] Globally serialized, sequential multi-root scan orchestration with independent-root continuation, persisted file errors, throttled durable live counters, and failed/cancelled preservation.
- [x] Database-enforced one-running-scan-per-root invariant plus an in-process global scan guard across manual, startup, and scheduled triggers.
- [x] Album-cover reconciliation and cleanup of unreferenced album, artist, and cover database rows.
- [x] Bounded keyset pagination for artist, album, and track queries with opaque filter-bound cursors.
- [x] Ranked, literal-prefix FTS5 track search with bounded queries and query-bound keyset cursors.
- [x] Hashed, revocable device access tokens with bcrypt storage, constant-time verification, and bearer middleware.
- [x] Short-lived one-time pairing codes and `POST /api/v1/pair` exchange with per-client rate limiting.
- [x] Administrator bootstrap, Argon2id password storage, HttpOnly session cookies, CSRF-protected mutations, and login rate limiting.
- [x] Admin device listing/revocation and pairing-code creation with QR payload generation.
- [x] Bearer-protected artist, album, track, and search HTTP handlers with repository pagination bounds and stable JSON errors.
- [x] Bearer-protected original artwork plus on-demand 128/256/512 px JPEG derivatives, immutable private caching, serialized generation, atomic installation, and a bounded private derivative cache.
- [x] Bearer-protected original FLAC/MP3 streaming with HTTP byte ranges and opened-file identity revalidation.
- [x] Server-rendered administration pages for bootstrap setup, login, device management, pairing-code creation, library-root management, and scan triggering.
- [x] Admin JSON endpoints for library-root CRUD, background scan triggers, and recent scan history.
- [x] Startup recovery for abandoned `running` scans and artwork-cache garbage collection.
- [x] Optional startup scan and periodic scheduler for configured library roots.
- [x] Admin scan error detail UI and search diagnostics.
- [x] Graphite admin UI polish with the Android Velin mark, uniform table-row alignment, compact success-state checkmarks, browser-localized relative timestamps, responsive table overflow, bounded live scan progress polling, and reverse-proxy-prefix-safe links, forms, redirects, assets, and API calls.
- [x] Admin security hardening with restrictive CSP/frame/MIME/referrer/permissions headers, no-store responses, structured credential-free pairing URL validation, and forwarded-header-resistant bounded rate limiting.
- [x] Scratch Docker image and Compose bind-mounts for read-only host music plus a private data directory, with a full developer procedure in `README.md` and operator notes in `docs/DEPLOYMENT.md`.
- [x] Kotlin/Compose Android project with Gradle Wrapper, API 37 build, polished graphite/ice-blue theme, accessible Material iconography, primary navigation shell, and unit tests.
- [x] Android QR/manual pairing with CameraX/ZXing, strict payload parsing, bounded OkHttp response handling, HTTP(S) URL normalization, safe errors, and Android Keystore-backed AES-GCM credential storage.
- [x] Server administration pairing defaults `server_url` to the editable browser-visible base (including a reverse-proxy path prefix) and renders an ephemeral QR image directly from the token-free `server_url`/`code` payload.
- [x] Authenticated Android OkHttp client with bearer injection, bounded JSON decoding, revocation handling, exact summary/revision loading, and full snapshot synchronization.
- [x] Android Home with exact server counts plus bounded recently added and per-process discovery album sections; searchable track results and cursor-paginated network views include loading, empty, and recoverable-error states.
- [x] Exported Android Media3 `MediaLibraryService` owning ExoPlayer and a `MediaLibrarySession`, with media audio focus, becoming-noisy handling, Android Auto media declaration, and foreground-service manifest declarations.
- [x] Server-bound Media3 `OkHttpDataSource.Factory` with in-memory bearer authorization, exact paired-origin stream validation, disabled redirects, and token-free FLAC/MP3 `MediaItem` construction.
- [x] Lifecycle-managed Media3 controller, playable Library/Search track rows, lazy notification permission, disconnect cleanup, and persistent buffering/play/pause mini-player.
- [x] Android Now Playing screen with title/artist/album/format, elapsed/duration/buffer polling, bounded seeking, replay, and back navigation.
- [x] Bounded Library/Search result queues with selected start index, automatic Media3 advance, queue-position state, and previous/next controls.
- [x] Authenticated size-specific Coil artwork with explicit 15%-memory/64-MiB-disk caches for rows, mini-player, and Now Playing plus a bounded/downsampled Media3 bitmap loader for notifications and lock-screen metadata.
- [x] Automatic near-end cursor pagination with inline loading and retry-only failures for library artists/albums/tracks and search results.
- [x] Artist detail with play-all, add-to-queue, and bounded artist-track loading; track detail with metadata, play action, and album/artist navigation.
- [x] Track-detail Play next and Add to queue actions, plus album/artist Add to queue, with bounded Media3 queue insertion.
- [x] Media3-backed queue screen with current-item highlighting, direct selection, safe removal, long-press drag reorder (shuffle off), shuffle, repeat Off/All/One, Clear, and one device-local Save/Load slot.
- [x] ExoPlayer buffering of 60–120 seconds with 120-second stream read timeouts; network-loss cancellation applies on devices, not emulators.
- [x] Android Auto media browse hierarchy with Room Recent and Discover folders (no library counts), then Albums and Artists, paginated children, FTS track search, and album-queue playback through the shared Media3 session.
- [x] App-private Room v1 catalog cache namespaced by SHA-256 of normalized server URL, NUL, and device ID, with the schema exported and the current namespace cleared on disconnect.
- [x] Complete 200-item-page snapshot downloads into staging generations, pre/post revision and exact-count verification, atomic activation, and retention of the previous snapshot after incomplete refreshes.
- [x] Room PagingSource-backed artist/album/track lists with page size 50, cached Home curation, and cached album detail when active; search, artist detail, and track detail remain network-backed; Android Auto Albums and Artists read Room while search stays network-backed; Auto home shelves and cached album/artist children read Room.
- [x] Empty-cache-only summary/shelf bootstrap and snapshot retries limited to transport failures plus HTTP 408/429/500/502/503/504 for at most three attempts.
- [x] One cancellable/coalescing artwork-prewarm worker triggered after startup and successful scan work, limited per pass to deterministic 1,024 referenced covers, 256/512 px variants, and a 10 ms pause after each variant.
- [x] GitHub Actions CI for server `gofmt`/`go test`/`go vet` and Android `testDebugUnitTest`/`lintDebug` (no emulator or DHU).

## Known issues and boundaries

- Internal indexing and browse functionality is reachable through admin HTTP/UI triggers and optional environment-driven startup and scheduled full-library scans; public status/pairing, admin JSON/HTML (including library roots and scan triggers), and bearer-protected library browse/search/artwork/streaming routes are wired.
- A process crash could leave a scan marked `running` until the next server startup; startup maintenance now marks those scans failed, records an interrupted error, and clears temporary markers.
- Artwork-cache files for removed cover rows are garbage-collected at startup.
- Album identity currently uses exact title, album-artist ID, and year without a schema-level unique constraint; correctness relies on the globally serialized scanner being the only album/track upsert writer. The bounded pool permits concurrent reads, not concurrent scans.
- Scan progress reports files seen and indexed, not a percentage: obtaining an exact total first would require a second complete filesystem walk. Counters are checkpointed at most once per second or per 100 files and finalized exactly after discovery/reconciliation.
- A physical 2,096-track remote library exposed manual pagination, cold repeated detail requests, and slow first-load full-size artwork. Phases 1–4 in `docs/PERFORMANCE.md` now provide automatic near-end pagination, bounded detail caches, parallel artist requests, size-specific artwork, exact summary counts, revision-verified Room snapshots, and local Paging. Initial snapshot activation and cached restart are device-validated; scrolling, detail reopening, artwork-prewarming, transfer, and memory measurements remain.
- Broad one-character search prefixes can rank many FTS rows; performance still needs benchmarking against the 100,000-track target and future authenticated endpoints need rate limits.
- MP3 duration is estimated from bitrate when Xing/VBRI frame counts are unavailable.
- There is no released-database upgrade fixture or backup/downgrade policy yet.
- The Go module path is `github.com/harald-mue/velin-music/server`.
- `go vet` is the only configured server static analysis. GitHub Actions runs `gofmt` (check), `make server-test`, `make server-lint`, `make android-test`, and `make android-lint` on `main` pushes and pull requests. The installed `staticcheck` binary is incompatible with the environment's Go 1.27 export-data format and is not part of CI.

## Important implementation notes

- The database file is `<VELIN_DATA_DIR>/velin.db`; symlink endpoints are rejected for the data directory, database file, and cover-cache directory. An owned data directory is tightened to mode `0700` on startup so Docker bind mounts are usable.
- Startup filesystem failures are logged without wrapped path-bearing errors.
- Root paths, track-relative paths, file sizes, and modification identity stay outside public library response models.
- Discovery and scanning are sequential and callback-based, keeping memory bounded for large libraries. Unchanged format/size/modification identity is marked seen without reparsing tags.
- Track upserts preserve opaque IDs for unchanged root-relative paths and update track FTS rows atomically.
- Parse/index errors for individual files are recorded and marked seen so a prior valid track is not deleted because one file became temporarily unreadable.
- Only successful `Scan.Finish` performs destructive reconciliation; explicit failures and cancellations retain existing tracks.
- `ScanAll` continues after independent root failures but stops on context cancellation/deadline.
- Persisted title/artist/album text has normalized whitespace and is limited to 1,024 Unicode code points; genre is limited to 256, and pagination cursors are also size-bounded.
- Browse and search pages default to 50 and reject limits above 200. Artist/album/track ordering uses a stable ID tie-breaker; browse cursors are filter-bound and search cursors are bound to the normalized query.
- Search requires valid UTF-8 and accepts at most 2,048 input bytes, 16 terms, and 64 Unicode code points per term. User text is converted to quoted literal token prefixes joined with `AND`; raw FTS5 operators are never accepted.
- Audio is FLAC-first with first-class MP3 support. Version one will serve original files and will not transcode.
- Android playback uses Media3 `MediaLibraryService` with a `MediaLibrarySession`. Bearer authorization is supplied through the data source rather than URLs. The service is exported for Android Auto; tokens must never appear in media IDs, metadata, or stream URLs.

## Verification status

Server:

- Formatting: PASS (`gofmt`)
- Build: PASS (`make server-build`)
- Tests: PASS (`make server-test`)
- Race tests: PASS (`cd server && go test -race ./...`)
- Configured static analysis: PASS (`make server-lint` / `go vet ./...`)
- Live status and SIGTERM shutdown smoke test: PASS
- Reachable vulnerability scan: PASS (`go run golang.org/x/vuln/cmd/govulncheck@latest ./...`); one unreachable Windows-only advisory remains in the Go-1.23-compatible transitive `golang.org/x/sys` version.
- Markdown link validation: PASS
- Full-tree whitespace validation: PASS (`git diff --cached --check` before the initial commit)
- CI workflow: present (`.github/workflows/ci.yml`); not yet observed on GitHub until the workflow runs on `main` or a pull request

Android:

- Toolchain: JDK 17, Android SDK Platform 37.0, Build-Tools 36.0.0, Gradle Wrapper 9.5.0, and Android Gradle Plugin 9.3.2.
- Build: PASS (`make android-build` / `./gradlew assembleDebug`)
- Tests: PASS (`make android-test` / `./gradlew testDebugUnitTest`)
- Static analysis: PASS (`make android-lint` / `./gradlew lintDebug`)
- Physical-device smoke test: PASS (server connection, library display, single-track playback, complete Room snapshot activation, and cached process restart). The measured snapshot contains 176 artists, 253 albums, and 2,096 tracks; a matching-revision restart made only status and summary requests. As reported on 2026-09-11, seeking, lock-screen, Bluetooth, headset controls, Android Auto in-car browse/playback, network interruption, and long-running playback have also been exercised successfully on real devices. Automated instrumentation, QR-camera behavior, and credential restoration after restart remain pending.
- Playback-resumption emulator validation: PARTIAL PASS. Unreachable-server cases: 21-item force-stop 1.395 s and reboot 2.800 s, Clear persistence, 500-item restoration in 1.884 s at about 176 MiB PSS, mixed FLAC/MP3 at 12,345 ms, idle-to-buffering Play, and notification metadata/actions. Reachable-server cases: force-stop while playing restored paused with 0 ms drift (34,992 ms in 1.485 s; 59,921 ms in 1.482 s; 40,997 ms at index 1 in 1.483 s, about 183 MiB PSS); Repeat Off auto-advanced to the next track; media-session pause/play after sleep/wake; Gearhead rebind without phone UI plus `dispatch play`; Auto Dashboard 304×390 and Facet 800×80 unique-id captures; live position advanced after Play. Bluetooth audio is emulator-N/A; visible lock-screen UI, disconnect/re-pair, reachable-server reboot, and signed physical-device validation remain.

## Recommended next task

Finish the remaining work-package-6 items in [`PLAYBACK_RESUMPTION_PLAN.md`](PLAYBACK_RESUMPTION_PLAN.md): a signed physical-phone pass covering process death, reboot, Clear, lock-screen/Bluetooth, and Android Auto, plus optional disconnect/re-pair isolation if destroying the current pairing is acceptable. Paired-emulator force-stop/reboot/Clear/500-item/mixed-format and reachable-server playing restore, auto-advance, media-key Play, and Auto/DHU resumption already pass.

## Recent work log

### 2026-09-12

- Ran playback-resumption work package 6 on the paired emulator. A 21-item queue restored paused after force-stop in 1.395 seconds and after reboot in 2.800 seconds with index, Shuffle, and Repeat All intact; Clear deleted the checkpoint and survived relaunch. A 134,504-byte 500-item fixture restored index 499 in 1.884 seconds at about 176 MiB emulator PSS, and a mixed FLAC/MP3 fixture restored index 1, 12,345 ms, and Repeat One exactly. Play advanced the idle restored player to buffering, and Media3 published title/artist/artwork/actions. Streaming was unreachable on that first pass.
- Continued package 6 after re-pairing with a reachable server. Force-stop while playing restored paused with 0 ms drift (34,992 ms in 1.485 s; 59,921 ms in 1.482 s; 40,997 ms at index 1 in 1.483 s, about 183 MiB PSS). Repeat Off plus a near-end seek auto-advanced to the next track. Media-session pause/play after sleep/wake resumed the restored item; Bluetooth audio is emulator-N/A and the keyguard did not stay showing. Force-stop without the phone UI restored through Gearhead rebind; `cmd media_session dispatch play` went `PLAYING` at the saved position and live streaming advanced. Auto Dashboard (304×390) and Facet (800×80) unique-id screencaps showed Velin. Disconnect/re-pair was skipped to keep pairing; signed physical-device validation remains.
- Recorded those package-6 latency and error numbers in the resumption plan, progress, roadmap, architecture, ADR-034, and Android README, then stopped further emulator validation. Remaining: signed physical-phone matrix plus optional disconnect/re-pair.

### 2026-09-11

- Recorded successful real-device validation of seeking, lock-screen, Bluetooth, headset controls, Android Auto in-car browse/playback, network interruption, and long-running playback. Defined the remaining playback-resumption work as six ordered packages in `docs/PLAYBACK_RESUMPTION_PLAN.md`, with explicit no-autoplay, credential isolation, Clear/disconnect, security, race, Media3 callback, and device acceptance criteria.
- Implemented playback-resumption work package 1: a separate versioned, atomic, bounded, credential-namespaced `PlaybackResumeStore`. Strict JSON decoding removes corrupt, unsupported, oversized, or mismatched records; empty queues delete the checkpoint; tests cover every repeat mode, duplicate IDs, malformed input, failed installation preserving the previous record, and a secret/URL-free schema.
- Implemented playback-resumption work package 2: `PlaybackService` captures queue/index/position/shuffle/repeat on relevant player events and every five seconds while playing, then submits immutable records to one conflating IO writer. Pause/seek and queue changes checkpoint promptly, empty queues order a delete, teardown captures before detaching and releasing the player, and network-loss stop preserves the queue. Safe format/cover IDs now travel in MediaItem extras; coordinator tests cover conflation, delete ordering, failure recovery, metadata projection, bounds, and ended-position reset.
- Implemented playback-resumption work package 3: service startup reads only the current credential namespace on IO, rebuilds stream/artwork metadata locally with current credentials, and restores queue/index/position/shuffle/repeat while remaining idle and paused. Queue-mutation callbacks cancel delayed restoration, a non-empty live queue always wins, and phone Play prepares an idle restored player. Unit tests cover restore-generation invalidation, current-credential URL rebuilding without tokens, FLAC/MP3 metadata, and idle-only preparation.
- Implemented playback-resumption work package 4: Media3 `onPlaybackResumption` now returns the same validated local queue/index/position to notification, Bluetooth, and Android Auto controllers through a cancellable future. Missing, invalid, unpaired, or credential-mismatched state fails safely; no disk access occurs on the main thread; token-free media items are rebuilt with current credentials. External Play prepares an idle restored queue after setting play intent. Future cancellation, empty-state failure, start-position mapping, and external preparation are unit-tested.
- Implemented playback-resumption work package 5: a custom command exposed only to Velin's own controller performs awaited generation-ordered checkpoint deletion. Clear reconciles and deletes automatic state; disconnect waits before credential removal with a credential-scoped direct fallback. Credential namespace changes detach and retire the old writer, delete old automatic state, clear old media items, and establish an isolated new restore scope; token rotation within one namespace keeps the scope. Tests prove deletion barriers, namespace isolation, and independence from the manual saved queue. The signed physical-device matrix remains pending.
- Split Queue playback and persistence controls into separate wrapping rows so Load is no longer hidden beyond a horizontal scroll area. The empty Queue intentionally leaves the remaining screen blank instead of repeating the visible Load action or showing an empty-state label.
- Replaced saved-queue restoration's serial per-track API loop with one active-Room-snapshot lookup and at most eight concurrent requests for unique cache misses. Queue order, duplicate entries, unavailable rows, and fallback behavior are covered by repository/resolver tests. After installing the signed APK on the connected physical phone, the existing cached slot was rendered and playback started within the first screenshot taken 500 ms after tapping Load.

### 2026-09-10

- Added `make server-package`, `make android-package`, and `make package` so `dist/` can hold a stripped host server binary and a signed release APK. `make android-package` sources `~/Keystore/velin-android-signing.env` and fails without a keystore. Debug `make android-build` is unchanged.
- Added `.github/workflows/ci.yml` so pushes and pull requests to `main` run server `gofmt`/`go test`/`go vet` and Android `testDebugUnitTest`/`lintDebug` via the existing Make targets. Race tests, `govulncheck`, emulator, and DHU jobs are out of this slice.
- Android Auto browse tiles use exported `content://com.haraldmue.velin.artwork/covers/{id}/256` because Gearhead fetches `iconUri` itself and does not render embedded browse bitmaps. Fetched covers are written into the Coil disk cache instead of leftover temp files. Snapshot changes notify Auto for Albums and Artists as well as root/Recent/Discover. The Auto recency tab is labeled **Recent**; phone Home stays **Recently added**. Auto album and artist items are browsable folders; Albums is a list. A tap loads Room tracks when the snapshot has that album.

### 2026-09-09

- Android Auto's browse root now prepends the same Room recently-added and Discover album shelves as phone Home (without artist/album/track counts). The Auto tab label is **Recent**. Albums, Artists, and search stay network-backed. Empty snapshot shelves are omitted; Auto does not start a snapshot sync from the playback service.
- `PlaybackService` reloads paired library clients when the Keystore token changes while Android Auto stays bound, and notifies the browse tree when the Room snapshot activates so DHU is not stuck on an empty Albums/Artists-only root.
- Android Auto Discover stays as its own tab even when those albums also appear under Recent. Browse album grids use `content://com.haraldmue.velin.artwork/covers/{id}/256` because Auto fetches `iconUri` itself and ignores embedded browse bitmaps. Track detail "Go to album" uses indexed `album_track_count`, not tag totals.

### 2026-09-08

- Stopped Compose playback-progress polling when Media3 is neither playing nor buffering, so the 500 ms position/buffer loop no longer runs while paused, idle, or ended.
- Tightened ExoPlayer buffering from 2–5 minutes to 60–120 seconds so LAN FLAC still has headroom above the 50-second defaults without holding a five-minute radio fill. Stream read timeout stays 120 seconds so idle-on-buffer reads are not aborted.
- Reduced playback-artwork CPU and idle work: embed validation uses bounds-only decode, the loader's two threads time out after one idle second, the artwork HTTP pool idles after 30 seconds, and previously embedded queue items are restored by media ID instead of scanning every queued track.
- Shortened idle OkHttp keep-alives from the five-minute default: Coil/library artwork and metadata pools drop unused sockets after 30 seconds; the stream pool waits 60 seconds so FLAC buffer pauses are not forced through a new handshake.

### 2026-09-06

- Diagnosed Android Auto's empty compact dashboard as legacy metadata fetching Velin's auth-gated `artworkUri`. The playback service now embeds a validated 256 px bitmap on the current item, clears that URI from published Media3 metadata, and keeps the token-free URL in extras for Compose. Architecture, security, and Android docs describe this extras fallback. After the URI-strip, emulator DHU still plays Overcompensate (Live In Mexico City): phone Now Playing/mini-player artwork, notification `android.largeIcon` 126×126, and Gearhead's 304×390 Dashboard virtual display all show the concert cover.
- Replaced Home's first-alphabetical album shelf with bounded **Recently added** and **Discover** sections. Album responses now expose safe index recency, Room schema 2 persists it through a migration-triggered refresh, and discovery wraps two bounded index ranges from a random per-process opaque ID instead of using a linear offset or full random sort.
- Preserved independent album, artist, track, and search scroll positions across tab changes and detail navigation, restored the submitted search text, and centered the Search loading indicator.
- Implemented performance Phase 4: a credential-derived, token-free Room namespace; complete revision/count-verified snapshots downloaded in 200-item pages; staging generations with atomic activation; 50-item Room PagingSource pages; empty-cache-only Home bootstrap; and exported Room schemas. Search, artist detail, track detail, and Android Auto remain network-backed, while album detail uses an active snapshot when available.
- Added bounded snapshot retry behavior: transport failures and HTTP 408, 429, 500, 502, 503, and 504 receive at most three attempts total; other failures do not retry. Disconnect clears the current namespace.
- Validated Phase 4 on a physical device: a complete 176-artist, 253-album, 2,096-track snapshot activated in about 18 seconds, persisted across process death, and a 1.056-second cold Android launch reused it with only status and summary requests.
- Added one cancellable/coalescing server artwork prewarmer after startup and successful scan work. Each deterministic pass covers at most 1,024 referenced covers at 256 and 512 px with a 10 ms pause after each variant.
- Diagnosed album pagination and artwork together on a physical device: rapid artwork replacement produced enough HTTP/2 stream resets and retry amplification to push an album page into its 15-second timeout. Artwork is now isolated on at most two HTTP/1.1 connections with no application retry, viewport cancellations remain cheap, and client disposal no longer closes TLS sockets on the main thread. A 12-swipe device run loaded two album pages in 952 ms and 4.0 s without another pagination error. Uncached server derivatives now use faster approximate bilinear scaling.
- Added credential-free debug request timings and identified server-side SQLite connection starvation: status completed in about one second while summary and album requests timed out after 15 seconds. The server now uses a bounded four-connection WAL pool with safety PRAGMAs applied per connection.
- Implemented performance Phase 3: authenticated exact library summary counts, an opaque trigger-maintained revision, a bounded Home album shelf, lazy per-section Android loading, and revision-based section/detail-cache invalidation.

### 2026-09-05

- Implemented performance Phases 1 and 2: 200-item artist/album and 100-item track pages, automatic near-end cursor loading with retry-only errors, atomic stale-safe merges, bounded detail caches, concurrent artist requests, fixed-size authenticated artwork derivatives, immutable caching, and explicit Coil cache limits.
- Profiled a physical phone against a 2,096-track remote library and added `docs/PERFORMANCE.md`: phased measurement, automatic pagination, bounded detail caching, artwork derivatives, exact summary counts, and the then-optional Room/Paging cache implemented on 2026-09-06.
- Fixed playback after re-pairing to a different server origin: the old MediaController is released, the playback service is stopped on credential changes, and each new stream data source reloads current Keystore credentials. Added origin-versus-public byte-range diagnostics, visible buffering state, and persistent Media3 error codes without changing the proven server streaming path.
- Documented the developer Docker workflow in `README.md`, including `make docker-save` → `dist/velin-server-local.tar.gz` for copying a linux/amd64 image to another PC.
- Added a `scratch` server image, Compose bind-mounts for `/music` (read-only) and `/data`, and English operator documentation in `docs/DEPLOYMENT.md` (ADR-024). Startup now tightens an owned data directory to mode `0700`.
- Album and artist detail screens can append their bounded track lists to the current queue as well as replace it with Play album / Play artist.
- Queue Save/Load is a single private JSON slot per paired device: Load greys missing library IDs, Save writes only available IDs, and file I/O runs off the main thread. Documented as ADR-021; playlists remain a v1 non-goal.

### 2026-09-04

- Queue chips now include Save, Load, and Clear. Save writes one private JSON slot of opaque track IDs plus titles; Load resolves IDs against the library, greys missing tracks, and omits them on the next save. Save stays disabled until the playable ID list differs from the last saved slot.
- Library refresh now keeps only the latest in-flight result, stays tappable, and shows an indeterminate progress bar while the library is syncing.

- Gave Home a three-column library overview, a near-black mini-player strip, and a top-bar refresh control beside server info.
- Added a Queue primary destination between Home and Library, and pinned every Material surface-container token to graphite `#111214` so the mini-player and navigation chrome no longer pick up blue elevation tints.
- Tightened the phone UI: restored graphite `#111214`, moved search into Library, compact Home album grid, centered album headers, custom seek bar, top-bar server status/info dialog, and width-based landscape/rail layouts.
- Refined the Compose phone UI with a cohesive graphite/ice-blue theme, improved typography and system-bar contrast, Material navigation/action/playback icons, a compact progress-aware mini-player, a focused Now Playing layout, richer Home statistics, polished search and media rows, and icon-based queue controls.
- Restored 120-second FLAC stream read timeouts and skipped emulator network-loss stream cancellation so host playback does not fail with “Playback failed”.
- Diagnosed a Desktop Head Unit ANR from a captured bugreport: Media3's legacy `onGetRoot` adapter blocked the app main thread while Velin dispatched its static root to an IO coroutine. The root now returns an already-completed future; a regression test asserts synchronous completion. DHU browse still requires a fresh manual validation after reinstall.
- Fail-fast library/artwork timeouts, cancellable OkHttp, and network-loss cancellation so wireless Android Auto taking Wi-Fi does not stall the phone UI or Auto browse session.
- Added a launcher icon and documented Android Auto developer “Unknown sources” so sideloaded debug builds can appear in the Auto media-app list.
- Migrated `PlaybackService` from `MediaSessionService` to `MediaLibraryService` / `MediaLibrarySession` so Android Auto, Compose, and system controls share one player and queue. Browse tree is Albums and Artists only (no fake Recently Played/Added). Android Auto desktop/in-car validation: NOT RUN (no Android Auto host in this environment).
- Added a Media3-backed queue screen with direct selection/removal plus shuffle and repeat controls.
- Added album detail navigation, bounded multi-page album loading, server-side disc/track keyset ordering, and complete album queue submission.
- Added paired-origin-only authenticated artwork loading for Compose and MediaSession metadata, with redirect rejection, response bounds, notification downsampling, and no tokens in URLs.
- Added bounded visible-result playback queues, automatic advance, queue-position state, and previous/next controls.
- Added a Now Playing screen with live progress, duration, buffer state, bounded seeking, replay behavior, and mini-player navigation.
- Connected Compose to `PlaybackService`, made Library/Search track rows playable, added lazy notification permission handling, and introduced a persistent mini-player.
- Added a paired-origin-only Media3 OkHttp data source, in-memory bearer headers, redirect rejection, safe stream URL construction, and Robolectric/MockWebServer coverage.
- Added the Media3 `MediaSessionService`/ExoPlayer foundation with audio focus, becoming-noisy handling, and current Android foreground-service declarations.
- Added the authenticated Android OkHttp client, bounded public-model decoding, revocation handling, and initial Home/Library/Search data screens.
- Added CameraX/ZXing pairing QR scanning with on-demand camera permission, strict token-free payload parsing, and manual-entry fallback; the admin page now renders the matching ephemeral QR image.
- Implemented Android manual pairing, bounded/safe pair responses, HTTP(S) server URL validation, trusted-LAN HTTP warnings, and AES-GCM device credential storage backed by Android Keystore.
- Bootstrapped the native Kotlin/Jetpack Compose Android app with API 37, a pinned Gradle Wrapper, the initial dark Velin theme, primary navigation, and passing build/test/lint validation.
- Added admin scan error detail pages and JSON endpoints, plus search diagnostics UI and `GET /api/v1/admin/search-diagnostics`.
- Added optional startup scanning (`VELIN_SCAN_ON_STARTUP`) and periodic full-library scheduling (`VELIN_SCAN_INTERVAL`) with overlap protection and graceful scheduler shutdown.
- Added startup maintenance to recover interrupted scans and garbage-collect unreferenced artwork-cache files.
- Added admin JSON and HTML routes for library-root management, background scan triggers, and recent scan history.

### 2026-09-03

- Created the repository documentation, Go server foundation, SQLite migrations, and status endpoint.
- Implemented validated roots, safe discovery, bounded FLAC/MP3 metadata parsing, transactional reconciliation, artwork caching, scan orchestration, and paginated browse repositories.
- Audited the complete implementation and documentation.
- Hardened managed-storage symlink handling, existing artwork verification, WebP validation, media identity checks, concurrent-scan prevention, cancellation state, multi-root failure behavior, relationship cleanup, album covers, and pagination filter scope.
- Reconciled README, architecture, API, database, security, roadmap, and decision documents with the executable and internal-package boundaries.
- Added bounded literal-prefix FTS5 search with weighted ranking, deterministic keyset pagination, query-bound cursors, and malformed-input tests.
- Added device token and pairing repositories, bearer authentication middleware, `POST /api/v1/pair`, and unauthorized/rate-limit tests.
- Added administrator bootstrap/login/session APIs, CSRF-protected device management, and pairing-code creation with QR payloads.
- Added bearer-protected library browse and search HTTP handlers for artists, albums, tracks, and FTS search.
- Added bearer-protected cover HTTP responses with cache-bound file resolution and MIME validation.
- Added bearer-protected FLAC/MP3 streaming with range support and opened-file identity checks.
- Added server-rendered `/admin` pages for setup, login, devices, and pairing with the existing session/CSRF contract.
