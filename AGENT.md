# Velin Agent Instructions

## Project identity

**Velin** — *Your music. Your network.*

Velin is a self-hosted music system for large personal libraries. A Go server indexes audio in configured local directories, persists searchable metadata, serves artwork and original audio, and provides a lightweight administration interface. A native Kotlin Android application connects through QR-based pairing and provides browsing and playback.

Velin is FLAC-first, but MP3 is a first-class supported format. Version one streams original files without transcoding and treats the music library as read-only.

## Engineering philosophy

- Prefer simple, robust, understandable designs over clever abstractions.
- Avoid premature abstraction and unnecessary dependencies; use the Go and Android standard/platform libraries where practical.
- Preserve backward compatibility unless a change is intentional and documented.
- Review security-sensitive code carefully and test critical behavior.
- Never expose arbitrary filesystem paths or allow path traversal.
- Never log secrets, bearer tokens, pairing codes, or credentials.
- Never modify users' music files.
- Keep memory use bounded for large libraries; use pagination and incremental work.
- Maintain useful test coverage around authentication, filesystem boundaries, indexing, and streaming.
- Do not leave core code in a knowingly broken state.
- Distinguish internal package capability from executable wiring and public API availability; never call an internal-only feature user-accessible or automatic.

## Mandatory workflow

Before starting work:

1. Read this file.
2. Read `docs/PROGRESS.md`.
3. Read relevant architecture and decision documents.
4. Inspect the existing implementation.
5. Understand existing tests before modifying behavior.

During work:

- Make cohesive, controlled changes.
- Run relevant tests and formatters.
- Update documentation when architecture or behavior changes.
- Keep the documented FLAC and MP3 support boundary accurate.

Before finishing a session:

- Build affected components.
- Run tests.
- Run formatters and configured static analysis.
- Update `docs/PROGRESS.md`.
- Update `docs/DECISIONS.md` for significant decisions.
- Update architecture, API, database, or security documentation when appropriate.
- Check Markdown links and stale implementation-status claims.
- Record a precise recommended next task.

## Documentation rule

Documentation must describe reality. Do not leave architecture documents describing systems that no longer exist. If implementation diverges from a document, update the document in the same change. All repository Markdown documentation is written in English.

## Progress tracking

`docs/PROGRESS.md` is the canonical cross-session development status. Updating it is mandatory before ending meaningful engineering work. Git history is not a substitute for a useful handoff.

## Scope and non-goals

Do not add cloud services, PostgreSQL, Redis, Elasticsearch, Kubernetes, microservices, or a message broker without a demonstrated requirement and a documented decision. Version-one non-goals include transcoding, offline downloads, multiple users, playlists, tag editing, internet metadata/cover fetching, Chromecast, iOS, and recommendation features.

## Useful commands

From the repository root:

```text
make help
make server-build
make server-run
make server-test
make server-test-race
make server-lint
make server-fmt
make android-build
make android-test
make android-lint
make test
make clean
```

The Android project uses its checked-in Gradle Wrapper and requires JDK 17 plus Android SDK Platform 37.0. Record Android build, test, and lint results separately from server validation.
