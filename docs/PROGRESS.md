# Velin Development Progress

Last updated: 2026-09-04

## Current status

The Go executable provides storage, indexing, authentication, administration, protected library APIs, artwork, and original-format streaming. The native Android project builds with Kotlin, Compose, and API 37 and provides a refined graphite/ice-blue visual system and primary navigation shell.

Android first-page library browsing/search, cursor pagination, album/artist/track details, bounded visible-result/album queues, queue reordering, and track-detail enqueue actions are wired through Media3, including authenticated artwork, Now Playing, seeking, automatic advance, previous/next, queue inspection/removal, shuffle, and repeat. Playback is owned by an exported `MediaLibraryService` that also exposes an Android Auto media library (Albums and Artists).

## Current milestone

**M10 — Playback** (active; authenticated queues, artwork, mini-player, Now Playing, seeking, previous/next, queue inspection/removal/reordering, shuffle, repeat, enqueue, and Android Auto media-library browsing are complete; broader device and in-car validation remains).

## Completed

- [x] Monorepo layout, English documentation, and persistent agent instructions.
- [x] Go module, development commands, environment configuration, and minimal server executable.
- [x] Private data-directory creation with permission and symlink checks.
- [x] Structured JSON lifecycle logging and graceful HTTP shutdown.
- [x] Pure-Go SQLite setup with WAL, foreign keys, busy timeout, and a single deterministic connection.
- [x] Ordered embedded migrations through `005_admin_auth.sql`.
- [x] `GET /api/v1/status` with automated tests.
- [x] Canonical SQLite-backed library-root management.
- [x] Callback-based FLAC/MP3 discovery with case-insensitive extensions and symlink skipping.
- [x] Bounded FLAC/MP3 tags, technical metadata, persisted text fields, and embedded-artwork extraction without audio-frame decoding.
- [x] Media identity revalidation between discovery and parsing.
- [x] Validated JPEG/PNG/GIF/WebP artwork, SHA-256 cache storage, existing-entry verification, and cover deduplication.
- [x] Identity-based skipping of unchanged files, transactional track/FTS upserts, durable scan markers, and complete-scan-only deletion.
- [x] Sequential multi-root scan orchestration with independent-root continuation, persisted file errors, and failed/cancelled preservation.
- [x] Database-enforced one-running-scan-per-root invariant.
- [x] Album-cover reconciliation and cleanup of unreferenced album, artist, and cover database rows.
- [x] Bounded keyset pagination for artist, album, and track queries with opaque filter-bound cursors.
- [x] Ranked, literal-prefix FTS5 track search with bounded queries and query-bound keyset cursors.
- [x] Hashed, revocable device access tokens with bcrypt storage, constant-time verification, and bearer middleware.
- [x] Short-lived one-time pairing codes and `POST /api/v1/pair` exchange with per-client rate limiting.
- [x] Administrator bootstrap, Argon2id password storage, HttpOnly session cookies, CSRF-protected mutations, and login rate limiting.
- [x] Admin device listing/revocation and pairing-code creation with QR payload generation.
- [x] Bearer-protected artist, album, track, and search HTTP handlers with repository pagination bounds and stable JSON errors.
- [x] Bearer-protected cover HTTP responses from the validated artwork cache with safe content types.
- [x] Bearer-protected original FLAC/MP3 streaming with HTTP byte ranges and opened-file identity revalidation.
- [x] Server-rendered administration pages for bootstrap setup, login, device management, pairing-code creation, library-root management, and scan triggering.
- [x] Admin JSON endpoints for library-root CRUD, background scan triggers, and recent scan history.
- [x] Startup recovery for abandoned `running` scans and artwork-cache garbage collection.
- [x] Optional startup scan and periodic scheduler for configured library roots.
- [x] Admin scan error detail UI and search diagnostics.
- [x] Kotlin/Compose Android project with Gradle Wrapper, API 37 build, polished graphite/ice-blue theme, accessible Material iconography, primary navigation shell, and unit tests.
- [x] Android QR/manual pairing with CameraX/ZXing, strict payload parsing, bounded OkHttp response handling, HTTP(S) URL normalization, safe errors, and Android Keystore-backed AES-GCM credential storage.
- [x] Server administration pairing result renders an ephemeral QR image directly from the token-free `server_url`/`code` payload.
- [x] Authenticated Android OkHttp client with bearer injection, bounded JSON decoding, revocation handling, and initial status/artist/album/track/search loading.
- [x] Android Home summary, searchable track results, and first-page Library views with loading, empty, and recoverable-error states.
- [x] Exported Android Media3 `MediaLibraryService` owning ExoPlayer and a `MediaLibrarySession`, with media audio focus, becoming-noisy handling, Android Auto media declaration, and foreground-service manifest declarations.
- [x] Server-bound Media3 `OkHttpDataSource.Factory` with in-memory bearer authorization, exact paired-origin stream validation, disabled redirects, and token-free FLAC/MP3 `MediaItem` construction.
- [x] Lifecycle-managed Media3 controller, playable Library/Search track rows, lazy notification permission, disconnect cleanup, and persistent buffering/play/pause mini-player.
- [x] Android Now Playing screen with title/artist/album/format, elapsed/duration/buffer polling, bounded seeking, replay, and back navigation.
- [x] Bounded Library/Search result queues with selected start index, automatic Media3 advance, queue-position state, and previous/next controls.
- [x] Authenticated Coil artwork for album/track rows, mini-player, and Now Playing plus a bounded/downsampled Media3 bitmap loader for notifications and lock-screen metadata.
- [x] Cursor-based load-more for library artists/albums/tracks and search results.
- [x] Artist detail with play-all and bounded artist-track loading; track detail with metadata, play action, and album/artist navigation.
- [x] Track-detail Play next and Add to queue actions with bounded Media3 queue insertion.
- [x] Media3-backed queue screen with current-item highlighting, direct selection, safe removal, long-press drag reorder (shuffle off), shuffle, and repeat Off/All/One.
- [x] Extended ExoPlayer buffering (2–5 minute window) with 120-second stream read timeouts; network-loss cancellation applies on devices, not emulators.
- [x] Android Auto media browse hierarchy for Albums and Artists, paginated children, FTS track search, and album-queue playback through the shared Media3 session.

## Deferred

- [ ] Add GitHub Actions CI for server and Android validation.

## Known issues and boundaries

- Internal indexing and browse functionality is reachable through admin HTTP/UI triggers and optional environment-driven startup and scheduled full-library scans; public status/pairing, admin JSON/HTML (including library roots and scan triggers), and bearer-protected library browse/search/artwork/streaming routes are wired.
- A process crash could leave a scan marked `running` until the next server startup; startup maintenance now marks those scans failed, records an interrupted error, and clears temporary markers.
- Artwork-cache files for removed cover rows are garbage-collected at startup.
- Album identity currently uses exact title, album-artist ID, and year without a schema-level unique constraint; correctness relies on the single SQLite connection and sequential scanner, so database concurrency must not be widened without strengthening this invariant.
- Broad one-character search prefixes can rank many FTS rows; performance still needs benchmarking against the 100,000-track target and future authenticated endpoints need rate limits.
- MP3 duration is estimated from bitrate when Xing/VBRI frame counts are unavailable.
- There is no released-database upgrade fixture or backup/downgrade policy yet.
- The Go module path is `github.com/harald-mue/velin-music/server`.
- `go vet` is the only configured static analysis and no CI workflow exists. The installed `staticcheck` binary is incompatible with the environment's Go 1.27 export-data format.

## Important implementation notes

- The database file is `<VELIN_DATA_DIR>/velin.db`; symlink endpoints are rejected for the data directory, database file, and cover-cache directory.
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

Android:

- Toolchain: JDK 17, Android SDK Platform 37.0, Build-Tools 36.0.0, Gradle Wrapper 9.5.0, and Android Gradle Plugin 9.3.2.
- Build: PASS (`make android-build` / `./gradlew assembleDebug`)
- Tests: PASS (`make android-test` / `./gradlew testDebugUnitTest`)
- Static analysis: PASS (`make android-lint` / `./gradlew lintDebug`)
- Physical-device smoke test: PASS (user-confirmed server connection, library display, and single-track playback). Android Auto Desktop Head Unit browse was attempted; its root-query ANR was diagnosed and fixed, but the fix still needs a fresh DHU browse/playback pass. Automated instrumentation, QR-camera behavior, credential restoration after restart, and broader media-control testing remain pending.

## Recommended next task

Finish broader lock-screen, notification, Bluetooth/headset, background-playback, and Android Auto in-car browse/playback device validation.

## Recent work log

### 2026-09-04

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
