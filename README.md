# Velin

*Your music. Your network.*

Repository: https://github.com/harald-mue/velin-music

Velin is a self-hosted music system for large personal libraries. The Go server indexes music from local filesystem roots, stores searchable metadata, serves artwork, and streams original audio. The native Android application will connect through QR-based device pairing and provide a focused browsing and playback experience.

Velin is **FLAC-first with first-class MP3 support**. Both formats are read-only from Velin's perspective and are streamed in their original form; version one does not transcode.

## Status

The repository contains a compilable Go server and a native Kotlin/Jetpack Compose Android client. The server provides authenticated library browsing, search, artwork, original-format streaming, administration, safe incremental indexing, optional startup scans, and scheduled scans. The Android app provides QR/manual pairing with Android Keystore-backed credentials and initial authenticated Home, Search, and Library screens; authenticated visible-result and album playback queues, cover artwork, album details, a mini-player, Now Playing, seeking, queue inspection/removal, shuffle, and repeat are wired; general pagination, artist/track details, queue reordering, and explicit enqueue actions remain to be implemented.

Current state and the next handoff are maintained in [`docs/PROGRESS.md`](docs/PROGRESS.md).

## Planned architecture

- A single Go executable and data directory.
- `net/http`, SQLite with FTS5, schema migrations, and server-rendered administration pages.
- Incremental, bounded indexing of configured roots, including FLAC and MP3 metadata and embedded artwork.
- Original HTTP byte-range streaming without transcoding.
- A native Kotlin/Jetpack Compose Android client using AndroidX Media3.
- Short-lived one-time QR pairing codes exchanged for revocable device tokens.

See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) and [`docs/ROADMAP.md`](docs/ROADMAP.md).

## Repository layout

```text
AGENT.md             Persistent engineering instructions
server/              Go server
android/             Native Kotlin/Jetpack Compose client
server/internal/admin/ Embedded administration templates and assets
docs/                Architecture, API, security, design, and handoff documents
Makefile             Development commands
```

## Development prerequisites

- Go 1.23.2 or newer.
- JDK 17, Android SDK Platform 37.0, and Build-Tools 36.0.0 for Android development.

No Node.js runtime, system Gradle installation, CGO toolchain, or external service is required. The Android build uses the checked-in Gradle Wrapper.

## Development commands

```sh
make help           # list available targets
make server-build   # build server/bin/velin-server
make server-run     # build and run the server
make server-test    # run Go tests
make server-test-race
make server-lint    # go vet
make server-fmt     # format Go sources
make android-build  # build the debug APK
make android-test   # run Android unit tests
make android-lint   # run Android lint
make test           # run server and Android unit tests
make clean          # remove build artifacts
```

Commands are intentionally only added when they perform real validation. Read [`AGENT.md`](AGENT.md) before making changes.

## License

Velin is released under the MIT License; see [`LICENSE`](LICENSE).
