# Velin server

Go module: `github.com/harald-mue/velin-music/server`

The server is a Go application built around the standard-library HTTP stack and a pure-Go SQLite driver. The executable initializes private managed storage and the database, logs lifecycle events as JSON, serves public status/pairing endpoints, admin JSON and HTML routes (including ephemeral pairing QR images, library-root management, and scan triggers), bearer-protected library browse/search/artwork/streaming endpoints, and shuts down cleanly on SIGINT/SIGTERM.

The `internal/library` package implements validated root persistence, bounded FLAC/MP3 discovery and metadata parsing, artwork caching with bounded 128/256/512 px derivatives and startup garbage collection, transactional scan reconciliation, interrupted-scan recovery, globally serialized sequential multi-root orchestration with throttled live counters, optional startup scanning, periodic full-library scheduling, paginated artist/album/track queries, and ranked FTS5 track search.

## Run

From this directory:

```sh
go run ./cmd/velin-server
```

Or from the repository root:

```sh
make server-build
./server/bin/velin-server
```

## Configuration

- `VELIN_HTTP_ADDR` — listen address; defaults to `:8080`.
- `VELIN_VERSION` — version returned by the status endpoint; defaults to `dev`.
- `VELIN_DATA_DIR` — private database and cache directory; defaults to the absolute form of `./data` relative to the process working directory.
- `VELIN_PUBLIC_URL` — optional public HTTP(S) base URL used by API clients that create pairing QR payloads without an explicit `server_url`. The browser administration form defaults to its current origin and remains editable.
- `VELIN_SECURE_COOKIES` — when `true`, admin session cookies are marked `Secure` even without TLS at the server process. Set this when HTTPS terminates at a reverse proxy; Velin does not trust forwarded headers by default.
- `VELIN_SCAN_ON_STARTUP` — when `true`, starts a background full-library scan after startup maintenance completes; defaults to `false`.
- `VELIN_SCAN_INTERVAL` — optional periodic full-library scan interval parsed with Go duration syntax (for example `6h` or `30m`); defaults to `0` (disabled). All overlapping scan triggers are skipped or rejected so only one filesystem scan runs at a time.

Configuration values are whitespace-trimmed. Blank values supplied only as whitespace are rejected, and externally visible address/version values are length-bounded.

`VELIN_DATA_DIR` is created with mode `0700`. If the process owns an existing directory, Velin tightens its mode to `0700`. Symlink endpoints remain rejected. SQLite is stored at `<VELIN_DATA_DIR>/velin.db`; a pre-existing symlink at that path is rejected.

## Docker

Build and run from the **repository root** (not this directory). Developer steps for image build, `.env`, target-host install, and container start are in [`../README.md`](../README.md). Mount and proxy notes are in [`../docs/DEPLOYMENT.md`](../docs/DEPLOYMENT.md).

## Reverse-proxy path prefixes

The administration frontend preserves an external path prefix in relative navigation, forms, redirects, assets, scan-status polling, and generated pairing URLs. For example, a reverse proxy may expose Velin at `https://host.example/velin/` and strip `/velin` before forwarding both `/velin/admin/*` and `/velin/api/*` to this server. The pairing form then defaults to `https://host.example/velin`, and Android appends its API paths below that base URL. The proxy must route both subtrees under the same prefix; the Go server's direct routes remain `/admin/*` and `/api/*`. Configure `VELIN_SECURE_COOKIES=true` for an HTTPS-terminating proxy.

## Implemented HTTP endpoints

```text
GET  /api/v1/status
POST /api/v1/pair
GET  /api/v1/library/summary
GET  /api/v1/artists
GET  /api/v1/artists/{id}
GET  /api/v1/albums
GET  /api/v1/albums/{id}
GET  /api/v1/tracks
GET  /api/v1/tracks/{id}
GET  /api/v1/search
GET|HEAD /api/v1/covers/{id}
GET|HEAD /api/v1/covers/{id}/{128|256|512}
GET  /api/v1/tracks/{id}/stream
HEAD /api/v1/tracks/{id}/stream
```

Bearer-protected library routes require `Authorization: Bearer <token>`. The summary endpoint returns exact public entity counts plus an opaque trigger-maintained revision for client cache invalidation.

Administration UI:

```text
GET  /admin/
GET  /admin/setup
POST /admin/setup
GET  /admin/login
POST /admin/login
POST /admin/logout
GET  /admin/devices
POST /admin/devices/{id}/revoke
GET  /admin/pairing
POST /admin/pairing
GET  /admin/roots
POST /admin/roots
POST /admin/roots/{id}/remove
POST /admin/roots/{id}/scan
POST /admin/scans
GET  /admin/scans/{id}
GET  /admin/search
```

Admin JSON routes:

```text
GET  /api/v1/admin/setup-status
POST /api/v1/admin/setup
POST /api/v1/admin/login
POST /api/v1/admin/logout
GET  /api/v1/admin/me
GET  /api/v1/admin/devices
DELETE /api/v1/admin/devices/{id}
POST /api/v1/admin/pairing-codes
GET  /api/v1/admin/library-roots
POST /api/v1/admin/library-roots
DELETE /api/v1/admin/library-roots/{id}
POST /api/v1/admin/library-roots/{id}/scan
POST /api/v1/admin/scans
GET  /api/v1/admin/scans
GET  /api/v1/admin/scans/{id}
GET  /api/v1/admin/scans/{id}/errors
GET  /api/v1/admin/search-diagnostics
```

Example status response:

```json
{"name":"Velin","status":"ok","version":"dev"}
```

See [`../docs/API.md`](../docs/API.md) and [`../docs/PROGRESS.md`](../docs/PROGRESS.md) for request/response details and canonical implementation status.

## Validation

From the repository root:

```sh
make help
make server-build
make server-test
make server-test-race
make server-lint
make test
make clean
```
