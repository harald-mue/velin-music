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

The current implementation includes configuration, private managed-storage preparation, SQLite initialization with embedded migrations, HTTP startup/shutdown, structured logging, `/api/v1/status`, `/api/v1/pair`, admin JSON and HTML authentication/device endpoints, validated library-root persistence, bounded FLAC/MP3 discovery, identity-based unchanged-file skipping, metadata parsing for tags and technical stream properties, transaction-safe track upserts/reconciliation, validated content-addressed artwork caching with startup garbage collection, interrupted-scan recovery, call-driven sequential scan orchestration with persisted scan errors, bounded keyset-paginated browse repositories, ranked FTS5 track search, hashed device tokens, pairing-code exchange, bearer authentication middleware, administrator sessions, and CSRF-protected admin mutations.

Only the status, pairing, admin JSON/HTML (including library roots and scan triggers), and bearer-protected library browse/search/artwork/streaming endpoints are wired into the executable. Optional startup scanning and periodic scheduling are controlled by environment variables.

### Velin Android

A native Kotlin/Jetpack Compose application. Responsibilities:

- device pairing and secure credential storage
- browsing artists, albums, and tracks
- search and artwork display
- playback and queue management
- `MediaSessionService` integration
- lock-screen, headset, and Bluetooth controls
- presentation of useful technical audio information without clutter

The Android project is a native Kotlin/Jetpack Compose application with a dark Velin theme and `Home / Search / Library` navigation. Server pairing is available through strict QR-camera scanning or manual entry and a bounded OkHttp request; normalized connection metadata and the issued device token are encrypted with an AES-GCM key held by Android Keystore. A separate authenticated OkHttp client injects the bearer header, bounds and validates JSON responses, and loads status plus initial artist, album, track, and search pages. Pagination, detail screens, artwork, and playback are not wired yet.

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
Android app
```

```text
Original FLAC or MP3 file
      ↓
Velin Server (authenticated HTTP Range)
      ↓
Media3 / ExoPlayer data source
      ↓
Android audio stack and MediaSession
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

The planned server is organized around clear responsibilities:

- `cmd/velin-server`: process lifecycle and dependency wiring.
- `internal/config`: process configuration and private managed-data-directory validation.
- `internal/db`: SQLite connection and migrations.
- `internal/library`: root validation/persistence, bounded discovery, file identity, parsing, reconciliation, artwork handling, scan orchestration, and library queries. It includes keyset-paginated artist/album/track browsing and bounded weighted FTS5 track search.
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

The target is at least 100,000 tracks across multiple roots and hundreds of GB to several TB. Scans must be incremental, use bounded concurrency, and avoid loading the complete library into memory. SQL pagination and indexes are mandatory for browse/search endpoints. Artwork is cached separately from the audio source.

Music files remain authoritative for audio and tags. SQLite stores the index and application state. A successfully completed scan reconciles additions, modifications, and deletions without modifying source files. The scanner is invoked through admin HTTP/UI triggers and optional environment-driven startup and scheduled full-library scans. Overlapping full-library scans are skipped in-process; per-root duplicate scans remain blocked by the database guard.
