# Velin Architecture

This document describes the intended architecture. Items marked as planned are not necessarily implemented; current implementation status is tracked in [`PROGRESS.md`](PROGRESS.md).

## Components

### Velin Server

A single Go executable with one configurable data directory. Responsibilities:

- library-root management
- safe filesystem scanning
- FLAC and MP3 metadata extraction
- embedded artwork extraction and artwork caching
- SQLite persistence and FTS5 search
- authentication, QR pairing, and device management
- versioned HTTP API
- original FLAC and MP3 streaming with HTTP byte ranges
- lightweight server-rendered administration web interface using `html/template`, embedded assets, and small amounts of JavaScript/HTMX where justified

The current implementation includes configuration, private managed-storage preparation, SQLite initialization with embedded migrations, HTTP startup/shutdown, structured logging, `/api/v1/status`, `/api/v1/pair`, admin JSON and HTML authentication/device endpoints, validated library-root persistence, bounded FLAC/MP3 discovery, identity-based unchanged-file skipping, metadata parsing for tags and technical stream properties, transaction-safe track upserts/reconciliation, validated content-addressed artwork caching with startup garbage collection, and bounded derivative prewarming. One cancellable worker coalesces triggers after startup and successful scan work, deterministically selects at most 1,024 referenced covers per pass, ensures 256 and 512 px variants, and pauses 10 ms after each variant. The implementation also includes interrupted-scan recovery, globally serialized sequential scan orchestration with throttled persisted progress and scan errors, bounded keyset-paginated browse repositories, ranked FTS5 track search, hashed device tokens, pairing-code exchange, bearer authentication middleware, administrator sessions, and CSRF-protected admin mutations.

Only the status, pairing, admin JSON/HTML (including library roots and scan triggers), and bearer-protected library browse/search/artwork/streaming endpoints are wired into the executable. Optional startup scanning and periodic scheduling are controlled by environment variables. The admin frontend uses prefix-preserving relative links and redirects and derives API/pairing bases from the browser-visible path before `/admin/`; a reverse proxy can therefore expose both `/admin/*` and `/api/*` under one stripped external prefix such as `/velin`.

The supported container deployment is a static binary in `scratch` with Compose bind-mounts: host music at `/music` (read-only) and managed state at `/data`. Operator steps are in [`DEPLOYMENT.md`](DEPLOYMENT.md).

### Velin Android

A native Kotlin/Jetpack Compose application. Responsibilities:

- device pairing and secure credential storage
- browsing artists, albums, and tracks
- search and artwork display
- playback and queue management
- `MediaLibraryService` integration for phone UI, system controls, and Android Auto
- lock-screen, headset, and Bluetooth controls
- presentation of useful technical audio information without clutter

The Android project is a native Kotlin/Jetpack Compose application with a graphite dark theme and `Home / Queue / Library` navigation (search is a Library section). Server pairing is available through strict QR-camera scanning or manual entry and a bounded OkHttp request; normalized connection metadata and the issued device token are encrypted with an AES-GCM key held by Android Keystore. Public artist, album, and track metadata is persisted as a Room snapshot under a SHA-256 namespace derived from normalized server URL, NUL, and device ID; the token is not an input. Refresh downloads complete 200-item API pages into a staging generation, verifies an unchanged summary revision and exact entity counts, then atomically activates that generation. Incomplete refreshes retain the previous snapshot. Room-backed library lists use PagingSource pages of 50. Home reads bounded **Recently added** and **Discover** sections from the active snapshot; recency comes from the newest indexed track in each album, while Discover starts from a random per-process opaque ID and wraps through two bounded index ranges without a linear offset scan or full random sort. Album detail reads Room when the album belongs to an active snapshot. Only an empty cache starts a parallel network summary and bounded album-shelf bootstrap. Search, artist detail, and track detail remain network-backed. Android Auto Albums and Artists read the active Room snapshot (falling back to the live catalog only when that snapshot has no rows); Auto search stays network-backed. Opening an album or artist that exists in the active snapshot uses Room tracks, as phone album detail does. Auto root **Recent** and **Discover** folders read the same Room home shelves as the phone and omit library counts. Snapshot requests make at most three attempts and retry only transport failures or HTTP 408, 429, 500, 502, 503, and 504. Disconnect clears the current cache namespace, and the Room version 2 schema is exported with a migration from version 1.

Playback is owned by an exported Media3 `MediaLibraryService` that hosts ExoPlayer, a `MediaLibrarySession`, audio focus, and becoming-noisy handling. Android Auto, the Compose `MediaController`, notifications, lock screen, and Bluetooth all attach to that single session and queue. Its server-bound OkHttp data source injects authorization in memory, accepts only exact paired-origin track-stream URLs, and disables redirects; media items contain opaque IDs and metadata but no token. The library session exposes a driver-safe browse tree whose root prepends Room **Recent** and **Discover** album folders (same bounded shelves as phone Home, without artist/album/track counts; Auto shortens the recency label from phone Home's **Recently added**) ahead of Room Albums (list) and Artists, with offset windows from the snapshot and FTS track search. Album and artist items are browsable folders, not immediately playable, so a tap loads the track list; when that album or artist is in the active snapshot, those children and play queues read Room instead of the live album-tracks API. Auto root keeps Recent and Discover as separate tabs even when the shelves share albums. Empty snapshot shelves are omitted. Android Auto's Gearhead bind keeps `PlaybackService` alive across pairing, so `stopService` after a credential change does not rebuild the session; the service reloads Keystore credentials on browse/connect and notifies Auto when the Room snapshot changes. A lifecycle-managed Compose `MediaController` submits the bounded visible Library/Search result as a queue, starts at the selected track, and drives a persistent mini-player plus a Now Playing screen with previous/next, queue position, position/duration/buffer state polled at 500 ms only while playing or buffering, and bounded seeking; disconnect stops and clears playback. Album/track rows, the mini-player, Now Playing, notifications, and lock-screen metadata can load covers through paired-origin-only clients. Artwork URLs contain no credentials; Coil and Media3 bitmap loaders inject authorization in memory, reject redirects, and bound notification decoding. Android Auto's compact dashboard fetches `artworkUri` itself and cannot authenticate those cover URLs, so the current item publishes a validated 256 px `artworkData` bitmap, clears the public URI, and keeps the token-free URL in extras for Compose; previously embedded queue items restore that URI. Browse-tree children expose `content://com.haraldmue.velin.artwork/covers/{id}/256` so Android Auto can fetch album-grid `iconUri` without a Bearer header; the exported provider serves Coil-cached files first, then an authenticated HTTP fetch written into that same disk cache. Now Playing still embeds `artworkData` and clears the public HTTP URI. Album rows open a detail view whose client follows album-scoped keyset cursors up to a hard 500-track queue bound when no active Room snapshot supplies the album tracks; the server orders those pages by disc number, track number, title, and opaque ID. Album and artist details can replace the current queue or append their bounded track lists. The Media3-backed queue screen exposes the current bounded queue, Clear, a device-local Save/Load slot (missing library tracks stay visible but greyed and are omitted on the next save), direct item selection, safe removal, long-press drag reorder when shuffle is off, shuffle, and repeat Off/All/One. Load restores matching tracks from the active Room snapshot in one query and resolves only unique cache misses over the network, with at most eight requests in flight while preserving queue order. Track detail can enqueue the next item or append to the bounded queue. ExoPlayer buffers 60–120 seconds (above the 50-second defaults, below the former 2–5 minute window) so LAN FLAC still has headroom without a five-minute radio fill. Stream reads use a 120-second timeout so FLAC playback can idle on a full buffer; on physical devices, losing the default network cancels in-flight library and stream calls. Playback resumption after process death is partially implemented. The service writes a bounded, credential-namespaced queue/index/position/shuffle/repeat checkpoint through one conflating IO writer, then reads it on IO at the next startup and locally rebuilds current-credential media items. It applies that state only while the new player is still empty, idle, and paused; queue-mutation callbacks invalidate delayed restoration, and phone Play prepares the idle queue. The same validated local state is exposed through Media3 `onPlaybackResumption` for notification, Bluetooth, and Android Auto controllers via cancellable IO work; external Play prepares an idle queue only after play intent is set. Clear and disconnect order automatic-state deletion through a trusted app-only session command before credential removal, with a direct credential-scoped fallback; namespace changes retire and delete the old writer before a new restore scope is created. Paired-emulator force-stop/reboot restoration, reachable-server playing restore with 0 ms drift, auto-advance, media-key Play, and Android Auto/DHU resumption are measured; disconnect/re-pair and signed physical-device validation remain in [`PLAYBACK_RESUMPTION_PLAN.md`](PLAYBACK_RESUMPTION_PLAN.md).

```text
Compose UI ───────┐
                  │
Android Auto ─────┼──> MediaLibraryService
                  │        │
System controls ──┘        ├── MediaLibrarySession
                           └── ExoPlayer
```

## High-level data flow

```text
Music directories
      ↓
Library scanner
      ↓
FLAC / MP3 metadata parser
      ↓
SQLite / FTS5 and artwork cache
      ↓
Versioned HTTP API
      ↓
Android Room snapshot
      ↓
Compose library UI
```

```text
Original FLAC or MP3 file
      ↓
Velin Server (authenticated HTTP Range)
      ↓
Media3 / ExoPlayer data source
      ↓
Android audio stack, MediaLibrarySession, Android Auto, and system controls
```

```text
Admin UI
      ↓
Short-lived one-time pairing secret
      ↓
QR code: server URL + secret, never bearer token
      ↓
Velin Android
      ↓
POST /api/v1/pair
      ↓
Hashed server token / securely stored device token
```

## Server boundaries and modules

The server is organized around clear responsibilities:

- `cmd/velin-server`: process lifecycle and dependency wiring.
- `internal/config`: process configuration and private managed-data-directory validation.
- `internal/db`: SQLite connection and migrations.
- `internal/library`: root validation/persistence, bounded discovery, file identity, parsing, reconciliation, artwork handling, scan orchestration, and library queries. It includes globally serialized, callback-driven scan orchestration, keyset-paginated artist/album/track browsing, and bounded weighted FTS5 track search.
- `internal/httpapi`: versioned JSON handlers for status, pairing, admin authentication/device management, and bearer-protected library browse/search/artwork/streaming, plus middleware helpers for protected routes.
- `internal/library`: includes `TrackStreamer` for root-bound original-file streaming with opened-file identity checks.
- `internal/auth`: device token hashing/verification, pairing-code exchange, administrator credentials/sessions, CSRF validation, revocation, and rate limiting.
- `internal/stream`: reserved; streaming is currently implemented in `internal/library`.
- `internal/admin`: server-rendered administration pages for setup, login, devices, and pairing.

These package boundaries are a target, not a reason to create empty packages before they are needed.

## Trust boundaries

1. The admin browser is an untrusted client of the server, even on a private LAN.
2. The Android device is an authenticated client with a revocable device token.
3. The server is trusted to read configured library roots but must not write music files.
4. The filesystem and configured library roots are a boundary: user configuration must be validated, paths must be canonicalized, and symlink behavior must be explicit.
5. The home network is not assumed safe against every participant. Public deployment requires TLS at a reverse proxy or directly at the server.

The API exposes opaque IDs and metadata, never local paths. Stream authorization resolves an opaque track ID through the database and checks that the resulting file still belongs to a configured root before opening it.

## Scale and lifecycle

The target is at least 100,000 tracks across multiple roots and hundreds of GB to several TB. Scans are incremental, globally serialized, stream filesystem discovery through a callback, and never load the complete library into memory. Live counters are checkpointed to the existing scan row at most once per second or per 100 processed files; the admin UI polls only a bounded recent-status response. A percentage is deliberately not fabricated because the final supported-file total is unknown until discovery completes. SQL pagination and indexes are mandatory for browse/search endpoints. Artwork is cached separately from the audio source. Prewarming is limited to the first 1,024 distinct referenced cover IDs in deterministic order so generating both selected variants cannot fill the 4,096-file derivative-cache budget in one pass.

Music files remain authoritative for audio and tags. SQLite stores the index and application state. A successfully completed scan reconciles additions, modifications, and deletions without modifying source files. The scanner is invoked through admin HTTP/UI triggers and optional environment-driven startup and scheduled full-library scans. All production scan triggers share an in-process global guard; the database additionally blocks duplicate running scans for the same root.
