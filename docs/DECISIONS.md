# Velin Architecture Decisions

Consequential decisions are recorded here so future agents do not revisit them without context.

## ADR-001 — Go for the server

Status: Accepted

Date: 2026-09-03

### Context

Velin needs a small self-hosted server with filesystem access, HTTP streaming, background scans, and a simple deployment model.

### Decision

Use recent stable Go, `net/http`, and the standard library where practical. Add a router or maintained parser only when a concrete complexity justifies it.

### Rationale

Go produces a single portable executable, has strong concurrency and HTTP primitives, and keeps operational dependencies low.

### Consequences

The server can ship as one executable plus one data directory. Features must justify third-party dependencies and their maintenance cost.

## ADR-002 — SQLite with FTS5

Status: Accepted

Date: 2026-09-03

### Context

A personal library needs durable metadata, search, migrations, and good behavior without operating a database service.

### Decision

Use SQLite as the primary database and SQLite FTS5 for library search.

### Rationale

SQLite fits a self-hosted single-server product, supports transactions and indexes, and avoids PostgreSQL/Redis/Elasticsearch infrastructure.

### Consequences

Connection, migration, backup, WAL, and concurrent scan/read behavior require deliberate configuration and tests. The current server uses a pure-Go SQLite driver and a single connection; this can be revisited only with measurements and tests.

## ADR-003 — Native Kotlin Android application

Status: Accepted

Date: 2026-09-03

### Context

Playback and Android system integration are core product requirements.

### Decision

Use Kotlin, Jetpack Compose, Material 3 foundations, AndroidX libraries, and platform-secure storage. Do not use Flutter, React Native, or a WebView for the application UI.

### Rationale

Native APIs provide first-class MediaSession, notification, Bluetooth, lifecycle, accessibility, and platform behavior.

### Consequences

The project requires Android-specific build tooling and must maintain a clear boundary between UI, data, and playback service.

## ADR-004 — Original audio streaming, FLAC-first with MP3 support

Status: Accepted

Date: 2026-09-03

### Context

Velin is designed around personal FLAC libraries, but MP3 remains a common and expected library format.

### Decision

Support FLAC as the primary format and MP3 as a first-class format. Stream original files over HTTP with byte ranges; do not transcode in v1.

### Rationale

Original streaming preserves source quality, keeps the server simple, and avoids CPU-heavy codec infrastructure. Supporting MP3 makes the product useful for mixed libraries without weakening the FLAC-first identity.

### Consequences

The indexer must parse and validate both formats, the API must emit `audio/flac` or `audio/mpeg`, and Media3 authorization/range behavior must be tested for both.

## ADR-005 — Lightweight server-rendered administration UI

Status: Accepted

Date: 2026-09-03

### Context

Administration needs dashboards, roots, scans, devices, and pairing but should not require a Node runtime or a separate frontend deployment.

### Decision

Use Go `html/template`, `go:embed`, and small amounts of vanilla JavaScript and/or HTMX.

### Rationale

This reduces operational complexity and is sufficient for an administration surface that is not the primary music experience.

### Consequences

The UI must keep progressive HTML behavior, protect browser mutations with CSRF, and avoid becoming a second application runtime.

## ADR-006 — QR pairing and hashed permanent device tokens

Status: Accepted

Date: 2026-09-03

### Context

The Android app should not require usernames/passwords, while long-lived access must be revocable and must not be exposed in QR data.

### Decision

The admin UI creates a random, one-time pairing secret valid for approximately five minutes. The QR contains only server URL and secret. `POST /api/v1/pair` exchanges it for a device bearer token with at least 256 bits of entropy. The server stores only a slow salted hash of the permanent token.

### Rationale

Short-lived one-time bootstrap limits QR exposure; per-device tokens enable revocation and device identity without sharing admin credentials.

### Consequences

Pairing uses expiration, single-use enforcement, rate limiting, bounded responses, and safe failure messages. The administration page renders the token-free payload as an in-memory QR image; the Android client scans it with CameraX/ZXing or accepts manual entry. Issued credentials are encrypted with AES-GCM using Android Keystore before local persistence. Tokens must never appear in URLs, QR payloads, UI, or logs. Camera and Keystore behavior require physical-device or emulator validation in addition to unit tests.

## ADR-007 — Monorepo

Status: Accepted

Date: 2026-09-03

### Context

Server, administration UI, Android client, and shared product documentation evolve together.

### Decision

Keep server, Android, and documentation in one repository with independent component build boundaries.

### Rationale

A monorepo gives agents one persistent source of truth and makes API/design changes visible across clients without requiring a multi-repository release process.

### Consequences

Component-specific commands and status reporting must clearly distinguish unavailable or unrun Android validation from server validation.

## ADR-008 — Pure-Go SQLite driver for the initial server

Status: Accepted

Date: 2026-09-03

### Context

Go does not include a SQLite driver in its standard library. The server should remain easy to build and ship as one executable without requiring a C toolchain or CGO at deployment time.

### Decision

Use the maintained `modernc.org/sqlite` pure-Go driver through `database/sql`. Configure SQLite for foreign keys, WAL, and a busy timeout. Initially limit the database handle to one connection so connection-local settings are deterministic.

### Rationale

The pure-Go driver preserves the single-executable deployment goal and avoids CGO portability requirements. A single connection is a deliberately conservative starting point for a self-hosted library server; concurrency should be revisited only after measurement.

### Consequences

The server takes a dependency on the SQLite driver and its transitive modules. Dependency updates require review. Database throughput and long-running scan behavior must be measured before changing connection limits.

## ADR-009 — Callback-based discovery with strict root boundaries

Status: Accepted

Date: 2026-09-03

### Context

Libraries may contain 100,000 or more tracks. Discovery must not load every candidate path into memory, must support both FLAC and MP3, and must not let symlinks silently expand a configured root.

### Decision

Validate and canonicalize roots before discovery, reject symlink roots, do not follow symlinked files or directories, and visit supported files through a callback. Discovery accepts `.flac` and `.mp3` case-insensitively and emits root-relative paths plus filesystem metadata only.

### Rationale

A callback gives the indexer bounded memory and immediate processing. Canonical roots and explicit symlink skipping make the filesystem boundary understandable and testable. Root-relative paths are sufficient for reconciliation while avoiding accidental API exposure of local paths.

### Consequences

Discovery is sequential and deterministic rather than concurrent; bounded parsing concurrency can be added above it later. Streaming must repeat safe path and file checks at open time because the filesystem can change after discovery.

## ADR-010 — Maintained metadata libraries with bounded parsing

Status: Accepted

Date: 2026-09-03

### Context

Velin needs reliable tags and technical properties for both FLAC and MP3, but it must not decode complete audio files merely to index them. Metadata and embedded artwork are untrusted input.

### Decision

Use `github.com/dhowden/tag` for common FLAC Vorbis Comment and MP3 ID3 metadata, and `github.com/mewkiz/flac` for FLAC StreamInfo properties. Parse MP3 frame headers directly for sample rate, channels, and duration estimates or Xing/VBRI frame counts. Bound tag and FLAC metadata reads to 8 MiB, and bound persisted user-facing metadata fields by Unicode code points. Validate extracted artwork separately before cache persistence.

### Rationale

Maintained format-aware libraries avoid reimplementing complex tag encodings. FLAC StreamInfo and MP3 frame headers expose technical properties without audio decoding. A hard metadata read budget limits malformed metadata work while leaving the large audio payload untouched.

### Consequences

Dependency updates require review and fixture-based tests for both formats. MP3 duration without a VBR frame-count header is an estimate. Artwork extraction remains a separate bounded task. The parser rechecks opened-file identity against discovery metadata, and parsed metadata is persisted transactionally by the indexer.

## ADR-011 — Scan markers for bounded reconciliation

Status: Accepted

Date: 2026-09-03

### Context

A large library cannot keep every discovered path in process memory. At the same time, deleting tracks that were not visited during a failed or partial scan would corrupt the index.

### Decision

Each root scan gets a durable `scan_runs` record and temporary `scan_seen_tracks` rows. Files whose format, size, and modification time still match are marked seen without metadata parsing. Every changed/new track upsert and its seen marker are committed in one transaction. Missing tracks are deleted only by a successful `Scan.Finish`; failed scans retain existing tracks and clear their markers.

### Rationale

SQLite provides durable bounded state and transactional atomicity without requiring an in-memory path set. Keeping the marker table separate also makes interrupted scans visible for later cleanup work.

### Consequences

Upserts are individually transactional, so a failed scan may contain successfully indexed changes but never performs destructive reconciliation. The scanner processes roots sequentially and cleans markers after successful or failed lifecycle completion; markers left by a process crash still require later cleanup.

## ADR-012 — Validated content-addressed artwork cache

Status: Accepted

Date: 2026-09-03

### Context

Embedded artwork is untrusted binary input. It must be reusable across tracks without modifying source audio files or allowing arbitrary cache paths, oversized payloads, or unsupported image data.

### Decision

Validate embedded artwork against supported image signatures and MIME types, limit it to 8 MiB, validate dimensions for decodable formats, and store it atomically under the managed data directory using a SHA-256 content-addressed filename. Persist the cache record in SQLite and refer to it from tracks by an opaque cover ID.

### Rationale

Content addressing naturally deduplicates identical covers and makes cache paths server-generated. Atomic writes avoid partial files after interruption. Size and dimension limits constrain memory/storage abuse while retaining common JPEG, PNG, GIF, and WebP artwork support.

### Consequences

Unreferenced cache files are garbage-collected at server startup. JPEG, PNG, GIF, and WebP container/header data and dimensions are validated without fully decoding image pixels. Existing cache entries are checksum-verified before reuse. Source audio files remain read-only.

## ADR-013 — Conservative scan error policy

Status: Accepted

Date: 2026-09-03

### Context

A large library can contain individual corrupt files, unreadable metadata, or files that disappear while a scan is running. Treating one bad file as a failed whole scan would reduce availability, while deleting an existing track after a partial scan would risk index loss.

### Decision

The orchestrator continues after per-file parse or index errors, persists a safe scan error, and marks that path as seen so an existing track is retained. Discovery failures, database failures, and cancellation fail the scan and never perform destructive reconciliation. A root is reconciled only after `Scan.Finish` succeeds.

### Rationale

This separates recoverable file problems from unsafe incomplete scans. Administrators can fix individual files and rescan without losing the rest of the library.

### Consequences

A completed scan can contain errors and therefore represent an incomplete subset of newly indexable files. Cancellation has its own persisted status. Multi-root orchestration continues after an independent root failure but stops on cancellation. Scan status and error counts must be visible in the future administration UI. Crash-left scan markers require later cleanup.

## ADR-014 — Keyset pagination for library queries

Status: Accepted

Date: 2026-09-03

### Context

Libraries may contain 100,000 or more tracks. Offset pagination becomes increasingly expensive and can produce unstable pages while scans update the index. API clients also must not receive database or filesystem identities through pagination state.

### Decision

Library query repositories use bounded keyset pagination with deterministic sort keys and opaque, URL-safe cursors. Artists sort by normalized name and ID; albums and tracks sort by display title and ID. Track cursors include their artist/album filter scope and are rejected if reused with different filters. Each query fetches at most one item beyond the requested limit to determine whether another page exists.

### Rationale

Keyset queries use indexed ordering boundaries rather than discarding an unbounded offset. A stable opaque ID tie-breaker prevents duplicate or skipped rows for equal names while keeping cursor structure an API implementation detail.

### Consequences

Pages are consistent for a moving index only at the item boundary, not as a historical snapshot. Cursors are encoded implementation state, not secrets or authorization. Future handlers must enforce authentication before calling these repositories.

## ADR-015 — Managed-storage and opened-file identity checks

Status: Accepted

Date: 2026-09-03

### Context

Canonical path validation alone is insufficient when filesystem entries can change between discovery, validation, opening, and reuse. Symlinks at managed write locations could also redirect SQLite or artwork writes outside the intended data directory.

### Decision

Reject symlink endpoints for the configured data directory, SQLite database file, and cover-cache directory. Discovery skips all symlink entries. Metadata parsing validates the root-bound path, opens the file read-only, then rechecks that the path is still a regular non-symlink entry referring to the opened file and that supplied discovery size/modification identity still matches. Existing content-addressed artwork files are checksum-verified before reuse.

### Rationale

Layered checks reduce path-redirection and stale-identity risks without introducing platform-specific filesystem APIs. Managed directories remain private, and all source audio operations remain read-only.

### Consequences

Operators cannot configure a symlink as the final data-directory or database path. Parent-directory replacement races cannot be eliminated portably with these checks alone; streaming must perform fresh equivalent validation and should use stronger platform facilities only if a portable design or demonstrated threat requires them.

## ADR-016 — Authentication before protected media APIs

Status: Accepted

Date: 2026-09-03

### Context

Browse, artwork, and streaming routes expose a private personal library. Implementing these routes before token validation would create a temporary unauthenticated contract that could be accidentally retained or deployed.

### Decision

Keep status as the only unauthenticated implemented endpoint. Build device authentication and pairing before wiring library metadata, artwork, or streaming handlers into the executable. Handler tests must include unauthorized access from their first implementation.

### Rationale

Security behavior is easier to preserve when it is part of the initial endpoint contract rather than retrofitted later. This also aligns Android integration with the final authorization-header behavior.

### Consequences

Internal browse and search repositories may be developed independently, but their HTTP exposure waits for the authentication milestone. The roadmap orders authentication before protected library and streaming APIs.

## ADR-017 — Literal prefix FTS search with rank-bound cursors

Status: Accepted

Date: 2026-09-03

### Context

Raw FTS5 query syntax is inappropriate for an untrusted API parameter: malformed operators produce database errors, advanced syntax creates an accidental public contract, and unbounded expressions can consume unnecessary work. Search pagination must also preserve relevance ordering without offsets.

### Decision

Convert input into lowercase Unicode letter/number terms, retaining combining marks only within a term. Punctuation acts as a separator. Reject invalid UTF-8, input over 2,048 bytes, more than 16 terms, terms over 64 code points, and input with no searchable terms. Generate quoted prefix terms joined with `AND`; never pass raw syntax through. Rank track rows with weighted BM25 in title, artist, album, and genre priority, followed by case-insensitive title and opaque ID. Search cursors carry rank and tie-breakers and are bound to a hash of the normalized query.

### Rationale

Server-generated expressions provide predictable search semantics and eliminate FTS operator injection. Prefix matching supports type-ahead use, while weighted rank and deterministic tie-breakers provide useful stable pages for large libraries.

### Consequences

All terms are currently required, and punctuation is not searchable. Search cursors are invalid when the normalized query changes. Floating-point rank is serialized in the opaque cursor and round-trips exactly enough for SQLite keyset comparison; pages still are not historical snapshots while scans modify the index. A short prefix can still match and rank many rows, so production handlers need authentication/rate limits and search performance must be measured on the 100,000-track target.

## ADR-018 — Single bootstrap administrator with session cookies and CSRF

Status: Accepted

Date: 2026-09-03

### Context

The administration surface needs authenticated device management and pairing-code creation before a server-rendered UI exists. Browser sessions must remain separate from device bearer tokens, and mutating actions must be protected against cross-site request forgery.

### Decision

Support exactly one bootstrap administrator created through `POST /api/v1/admin/setup` when no `admin_users` row exists. Store Argon2id password hashes, issue 24-hour HttpOnly `velin_admin_session` cookies backed by hashed session tokens in SQLite, and require an `X-CSRF-Token` header matching the active session for logout, device revocation, and pairing-code creation. Expose admin JSON routes under `/api/v1/admin/*`; device bearer tokens are rejected on those routes.

### Rationale

A single bootstrap account keeps first-run setup simple for self-hosted deployments while separating browser and device trust domains. Cookie sessions with CSRF headers provide CSRF protection without embedding secrets in URLs or relying on device tokens for admin work.

### Consequences

Additional administrators, password rotation, and session listing/revocation remain future work. Pairing QR payloads require a caller-supplied or configured public `server_url` because the process may not know its external address. The server-rendered administration UI in M7 should reuse the same cookie and CSRF contract rather than introducing a second admin auth mechanism.

## ADR-019 — Environment-driven startup scan and periodic scheduler

Status: Accepted; overlap policy superseded by ADR-022

Date: 2026-09-04

### Context

Self-hosted deployments benefit from automatic library refresh after restarts and on a predictable interval, but many installations also want explicit control over when background work runs. Full-library scans are expensive and must not overlap in-process even though per-root duplicate scans are already blocked in SQLite.

### Decision

Expose optional process-level scan automation through `VELIN_SCAN_ON_STARTUP` (default `false`) and `VELIN_SCAN_INTERVAL` (default `0`, disabled). After startup maintenance completes, an enabled startup scan launches one non-blocking full-library scan. A positive interval starts a background scheduler that triggers guarded full-library scans on each tick. `ScanService` tracks an in-process `allScan` flag so overlapping full-library scans are skipped; admin and scheduler triggers share the same guard. Scheduler shutdown is tied to HTTP server lifecycle through `httpapi.Server.Stop()`.

### Consequences

Scan automation settings are not persisted in the database and require process restart to change. Scheduled ticks that arrive during an active full-library scan are skipped with an info log. Admin `POST /api/v1/admin/scans` now returns `409 scan_running` when a full-library scan is already active. The original implementation allowed per-root scans during a full-library scan. ADR-022 supersedes that overlap policy: all production scan triggers now share one global guard.

## ADR-020 — MediaLibraryService for Android Auto and phone playback

Status: Accepted

Date: 2026-09-04

### Context

Velin already owned playback in a Media3 `MediaSessionService` consumed by Compose through `MediaController`. Android Auto media apps need a browsable library as well as a shared player. Introducing a second service or a Car App Library template UI would split playback state and fight the host-provided driver-safe interface.

### Decision

Migrate the existing `PlaybackService` to Media3 `MediaLibraryService` with a `MediaLibrarySession`. Keep a single ExoPlayer and a single queue. Expose only server-backed Albums and Artists (plus FTS track search). Resolve Android Auto play requests through the existing authenticated stream data source. Export the service with the Media3 library-service and platform MediaBrowser intent filters, and declare the Android Auto media capability. Do not add an Android Automotive OS module or a custom Auto UI.

### Rationale

`MediaLibrarySession` is the supported way for Android Auto to browse a media app while phone UI, notifications, lock screen, and Bluetooth continue to use the same session. Reusing `LibraryGateway` avoids a second HTTP stack. Prefixed browse IDs stay separate from token-free playback `MediaItem` IDs used by the phone queue.

### Consequences

The playback service is exported. Tokens must never appear in media IDs, metadata, or URLs; authorization remains an in-memory OkHttp/Media3 header. Recently Played/Added are omitted until the server provides those collections. Playback resumption after process death remains unimplemented. Android Automotive OS is still out of scope.

## ADR-021 — One device-local saved playback queue

Status: Accepted

Date: 2026-09-05

### Context

The phone Queue tab needed a way to restore a recent playback list after clearing it or leaving the app. Named server playlists are a v1 non-goal. Missing library tracks after a later scan must not crash Load.

### Decision

Persist a single saved-queue JSON file in the app’s private files directory, keyed by opaque device ID. Store only opaque track IDs plus previously seen title/artist text. Load resolves each ID through the authenticated track API; HTTP 404 rows stay visible and greyed and cannot start playback. Save writes only currently available IDs and is enabled when that ID list differs from the last successful save. Clear empties the live Media3 queue without deleting the slot.

### Consequences

This is not a playlist library: there is one slot per paired device on that phone, no names, and no server copy. Unavailable rows disappear from the slot on the next save. Tokens never enter the file.

## ADR-022 — Globally serialized scans with bounded live progress

Status: Accepted

Date: 2026-09-05

### Context

A filesystem scan can run for hours on a library with 100,000 or more tracks. The administration UI previously showed zero counters until completion, while separate per-root and full-library triggers could run different roots concurrently and multiply filesystem, metadata-parser, artwork-cache, and SQLite pressure. Computing a percentage by pre-counting would double the filesystem walk and still race with source changes.

### Decision

Serialize all production scan work through one `ScanService` guard shared by per-root, full-library, startup, and scheduled triggers. Create the first persisted `running` row before a trigger returns. Keep callback-driven discovery and sequential root processing so memory does not grow with library size. Checkpoint files-seen and files-indexed counters to the existing scan row after at most 100 processed files or one second, then recompute exact final counts from reconciliation state. The administration page polls the bounded ten-run JSON response with backoff and displays an indeterminate progress bar plus counters rather than a fabricated percentage.

### Rationale

One active scanner gives predictable I/O and database pressure on small self-hosted systems. Throttled constant-size status writes make long-running work observable without repeatedly counting a potentially huge marker table or retaining paths in memory. Synchronous run creation closes the redirect race in which the UI could miss a newly started scan.

### Consequences

Scanning different roots in parallel is intentionally unavailable. Conflicting manual requests return `scan_running`; startup and scheduled triggers are skipped. The UI may show counters up to one second or 99 files behind and reloads only at root-run boundaries. Existing indexed content remains readable during discovery, and destructive reconciliation still occurs only after a complete successful root scan.

## ADR-023 — Preserve browser-visible reverse-proxy path prefixes

Status: Accepted

Date: 2026-09-05

### Context

Velin may later be exposed locally below a path such as `https://home.example/velin/` while a reverse proxy strips `/velin` before forwarding requests. Root-absolute admin links, redirects, assets, and API calls would escape that prefix, and using only `window.location.origin` in a pairing payload would send Android to the wrong API path.

### Decision

Use document-relative URLs throughout server-rendered administration pages and relative HTTP `Location` responses for admin redirects. Derive the browser-visible server base by removing the final `/admin/...` portion from the current URL, preserving scheme, host, port, and preceding path. Use that base for scan-status calls and as the editable pairing default. Android continues to append endpoint segments to, rather than replace, the normalized server base path.

### Consequences

A reverse proxy can mount Velin below one external prefix when it forwards both `<prefix>/admin/*` and `<prefix>/api/*` after stripping the same prefix. The Go server still registers direct `/admin/*` and `/api/*` routes and does not infer routing from untrusted forwarded-prefix headers. Deployments must not expose the two route groups below different external prefixes.
