# Velin Roadmap

Milestones are intentionally broad. **Complete** means implemented and validated; **active** means the current focus; **planned** means not started. Internal capability does not imply that an HTTP endpoint or automatic process trigger exists.

## M0 — Repository foundation — **complete**

- monorepo layout and persistent engineering documentation
- Go module, development commands, and minimal server executable
- Android planning boundary documented without unverifiable generated files

## M1 — Server foundation — **complete**

- environment configuration and private managed-storage handling
- structured lifecycle logging
- SQLite connection and embedded schema migrations
- public health/status endpoint
- graceful shutdown

## M2 — Music indexing internals — **complete**

- validated library-root persistence
- bounded FLAC and MP3 discovery with canonical-root and symlink protection
- bounded FLAC and MP3 tag/technical metadata parsing without decoding audio frames
- identity-based unchanged-file skipping, transaction-safe track upserts, and complete-scan-only deletion
- embedded artwork validation, content-addressed caching, and cover association
- sequential multi-root orchestration, persisted per-file errors, and scan counts
- explicit failed/cancelled scan preservation, one-running-scan-per-root database guard, and globally serialized production scan orchestration

The scanner is call-driven internal functionality. Admin/API triggers, optional startup scanning, and periodic scheduling are wired; scan settings are not stored in SQLite.

## M3 — Library database and search — **complete**

- **Complete:** artist, album, track, cover, scan, and FTS schema
- **Complete:** paginated artist, album, and track query repositories with stable opaque IDs and filter-bound cursors
- **Complete:** relationship cleanup and deterministic album-cover reconciliation
- **Complete:** bounded literal-prefix FTS5 track search with weighted ranking and query-bound pagination

## M4 — Authentication and pairing — **complete**

- **Complete:** hashed, revocable device access tokens with bcrypt storage and bearer middleware
- **Complete:** short-lived one-time pairing codes and `POST /api/v1/pair` exchange with rate limiting
- **Complete:** administrator bootstrap, Argon2id credentials, HttpOnly sessions, CSRF protection, and login rate limiting
- **Complete:** admin device-management JSON endpoints and pairing-code creation with QR payload generation

Authentication precedes protected library, artwork, and streaming endpoints so no temporary unauthenticated API contract is introduced.

## M5 — Protected library and artwork API — **complete**

- **Complete:** authenticated paginated artist, album, track, and search endpoints with stable JSON error mapping
- **Complete:** authenticated cover artwork responses with safe content types and cache-bound file resolution

## M6 — Streaming API — **complete**

- **Complete:** authenticated original FLAC and MP3 streaming without transcoding
- **Complete:** GET/HEAD byte ranges, `Content-Length`, validators, and opened-file identity revalidation

## M7 — Administration frontend — **complete**

- **Complete:** dashboard, bootstrap setup, login, devices, pairing-code pages, library-root/scan management, scan error detail, and search diagnostics using the existing session/CSRF contract
- **Complete:** shared Android/admin Velin mark, consistently aligned data tables, and bounded polling of throttled live scan counters

## M8 — Android foundation — **complete**

- **Complete:** compilable Kotlin/Compose project, Gradle Wrapper, initial dark design system, and primary navigation shell
- **Complete:** QR-camera and manual HTTP(S) pairing, bounded response handling, server URL validation, and Android Keystore-backed credential storage
- **Complete:** authenticated OkHttp client with bounded model decoding, revocation handling, and navigation backed by initial server data

## M9 — Android library UX — **complete**

- **Complete:** revision/count-verified Room snapshots, atomic generation activation, 50-item PagingSource pages, cached Home and album detail, and empty-cache network bootstrap
- **Complete:** Home loads exact counts plus bounded recently added and discovery album sections; artist, album, and track tabs use local Paging; network-backed search and detail screens are complete
- **Complete:** authenticated artwork in album/track rows, mini-player, Now Playing, and MediaSession metadata
- **Complete:** loading, empty, and recoverable-error states for initial pages

## M10 — Playback — **active**

- **Complete:** AndroidX Media3/ExoPlayer hosted by an exported `MediaLibraryService` / `MediaLibrarySession`, with audio focus and becoming-noisy handling
- **Complete:** server-bound authenticated Media3 OkHttp data source and token-free FLAC/MP3 media-item construction
- **Complete:** lifecycle-managed Compose `MediaController`, Library/Search track-tap playback, lazy notification permission, and persistent play/pause mini-player
- **Complete:** Now Playing with elapsed/duration/buffer state, replay, and bounded seeking
- **Complete:** bounded visible-list/search queues with selected start index, automatic advance, queue position, and previous/next controls
- **Complete:** authenticated token-free artwork metadata with redirect-safe Coil and Media3 loaders
- **Complete:** album-specific queues in disc/track order, loaded across bounded cursor pages, with Play album and Add to queue
- **Complete:** Media3-backed queue display, Clear, one device-local Save/Load slot (grey unavailable IDs, save drops them), direct selection, safe item removal, long-press drag reorder (shuffle off), shuffle, repeat Off/All/One, track-detail Play next / Add to queue, and album/artist Add to queue
- **Complete:** ExoPlayer buffering of 60–120 seconds with 120-second stream reads; device network-loss cancellation does not run in the emulator
- **Complete:** Android Auto media-app declaration; browse root uses Room Recent and Discover shelves (no library counts) plus server-backed Albums/Artists with paginated children, FTS track search, Room album/artist children when cached, and album-queue playback through the shared session
- **Complete:** physical-device validation of seeking, lock-screen, Bluetooth, headset controls, Android Auto in-car browse/playback, and long-running background behavior (reported 2026-09-11)
- **Partial:** local checkpointing, paused phone/Media3 system resumption, and credential lifecycle deletion are implemented; paired-emulator force-stop/reboot/Clear/500-item/mixed-format restoration and reachable-server playing restore (0 ms drift, 1.48 s), auto-advance, media-key Play, and Android Auto/DHU resumption pass, while disconnect/re-pair, Bluetooth, visible lock-screen UI, and the signed physical-device matrix remain in [`PLAYBACK_RESUMPTION_PLAN.md`](PLAYBACK_RESUMPTION_PLAN.md)
- **Complete:** authorization headers for original FLAC/MP3 streams

## M11 — Polish and operations — **planned**

- **Active:** Android large-library performance work following [`PERFORMANCE.md`](PERFORMANCE.md): automatic cursor prefetch, bounded detail caches, artwork derivatives, exact summary counts, revision-verified Room snapshots, atomic activation, and local Paging are implemented; physical remeasurement and 2,000/100,000-track fixtures remain
- **Complete:** one cancellable/coalescing server artwork-prewarm worker after startup and successful scan work, bounded to 1,024 referenced covers and 256/512 px variants per pass
- accessibility and purposeful animation
- robustness and integration testing
- **Partial:** scratch Docker image, Compose bind-mounts for music and data, and operator documentation in [`DEPLOYMENT.md`](DEPLOYMENT.md); reverse-proxy path prefixes are documented; published registry images and backup automation remain open
- **Complete:** GitHub Actions CI for server `gofmt`/`go test`/`go vet` and Android unit tests/`lintDebug` (no emulator or DHU)

Explicit v1 non-goals remain transcoding, offline downloads, multiple users, playlists, tag editing, internet metadata/cover fetching, Chromecast, a custom Android Auto template UI, Android Automotive OS, iOS, and recommendations. The phone app’s one local saved-queue file is not a playlist feature.
