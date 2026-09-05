# Velin API Design

The API is versioned below `/api/v1`. This is an initial design; **public status/pairing, admin JSON (including library-root management and scan triggers), bearer-protected library browse/search endpoints, cover artwork responses, and original FLAC/MP3 streaming are currently implemented**. Breaking changes require a new API version or an explicitly documented migration.

## Conventions

- JSON is used for control and metadata responses.
- IDs are opaque, stable identifiers; clients must not infer database keys or paths from them.
- Collection endpoints use `limit` plus an opaque, URL-safe `cursor`. The internal repositories default to 50 items and reject limits above 200; HTTP handlers will preserve those bounds.
- Errors use a stable JSON shape such as `{ "error": { "code": "...", "message": "..." } }`; messages must not disclose local paths or secrets.
- All timestamps are ISO 8601 UTC strings.
- Unknown response fields should be ignored by clients.

## Public status

```text
GET /api/v1/status
```

No client token is required. The initial response is:

```json
{"name":"Velin","status":"ok","version":"dev"}
```

As dependencies are added, `status` must not disclose secrets or filesystem paths.

## Pairing

```text
POST /api/v1/pair
```

Unauthenticated, but rate-limited. Accepts the server address context and a short-lived one-time pairing secret. On success returns a device-specific bearer token once, plus a device identifier and server metadata. The QR payload contains only a server URL and the pairing secret; it never contains the resulting bearer token.

The token is sent on subsequent Android requests as:

```text
Authorization: Bearer <token>
```

Pairing codes expire after approximately five minutes and are single-use. Administrators create codes through `POST /api/v1/admin/pairing-codes`; Android clients exchange them through `POST /api/v1/pair`.

## Admin authentication and device management

Admin JSON endpoints use an HttpOnly `velin_admin_session` cookie. Mutating requests require the `X-CSRF-Token` header returned from setup, login, or `GET /api/v1/admin/me`. Device bearer tokens are not valid admin credentials.

```text
GET  /api/v1/admin/setup-status
POST /api/v1/admin/setup
POST /api/v1/admin/login
POST /api/v1/admin/logout
GET  /api/v1/admin/me
GET  /api/v1/admin/devices
DELETE /api/v1/admin/devices/{id}
POST /api/v1/admin/pairing-codes
```

`GET /api/v1/admin/setup-status` returns `{"needs_setup": true}` until the first administrator is created. `POST /api/v1/admin/setup` accepts `username` and `password` (minimum 8 bytes), creates the sole bootstrap account, sets the session cookie, and returns `username`, `csrf_token`, and `expires_at`. Subsequent administrators are not supported in v1. `POST /api/v1/admin/login` behaves similarly once setup is complete.

`POST /api/v1/admin/pairing-codes` accepts `device_name` and optional `server_url`. When `server_url` is omitted, the server uses `VELIN_PUBLIC_URL`. The response includes the one-time `code`, `expires_at`, and a `qr_payload` object containing only `server_url` and `code`. The browser administration form pre-fills `server_url` from the address-bar scheme, host, port, and any path prefix before `/admin/`, and allows the administrator to override it. The authenticated result page renders the resulting payload as an ephemeral QR image for the Android client; the image is not persisted. `GET /api/v1/admin/devices` lists registered devices without secret material. `DELETE /api/v1/admin/devices/{id}` revokes a device token.

Library-root management and scan triggering are admin-only. Paths are returned only on these endpoints and in the administration UI, never on device library routes.

```text
GET    /api/v1/admin/library-roots
POST   /api/v1/admin/library-roots
DELETE /api/v1/admin/library-roots/{id}
POST   /api/v1/admin/library-roots/{id}/scan
POST   /api/v1/admin/scans
GET    /api/v1/admin/scans?limit=<n>
GET    /api/v1/admin/scans/{id}
GET    /api/v1/admin/scans/{id}/errors?limit=<n>
GET    /api/v1/admin/search-diagnostics?q=<text>
```

`GET /api/v1/admin/library-roots` returns configured roots with opaque IDs, absolute filesystem paths, and `created_at`. `POST /api/v1/admin/library-roots` accepts `path`, validates that it names an existing non-symlink directory, and returns `201` with the created root. Duplicate paths return `409` with `root_exists`. `DELETE /api/v1/admin/library-roots/{id}` removes the root and its indexed tracks without modifying source files.

`POST /api/v1/admin/library-roots/{id}/scan` and `POST /api/v1/admin/scans` start background scans and return `202 Accepted`. Scan work is globally serialized; any per-root, full-library, startup, or scheduled trigger received while another scan is active returns or is treated as `scan_running`. `GET /api/v1/admin/scans` returns a bounded recent list with status, timestamps, and live file counters. Running counters are persisted at a throttled cadence and finalized exactly when reconciliation completes. `GET /api/v1/admin/scans/{id}` returns one scan run. `GET /api/v1/admin/scans/{id}/errors` returns persisted scan diagnostics with root-relative `source_name`, `error_code`, `message`, and `created_at`, plus a `total` count. `GET /api/v1/admin/search-diagnostics` validates and explains how `q` is compiled for FTS5 and returns up to ten ranked preview tracks when the query is valid.

Login and pairing endpoints are rate-limited per client. Set `VELIN_SECURE_COOKIES=true` when TLS terminates at a reverse proxy, or terminate TLS in Velin directly, to mark session cookies `Secure`. Velin does not trust client-supplied forwarded headers.

## Library resources

All endpoints below require a valid, non-revoked device token unless noted otherwise.

```text
GET /api/v1/artists
GET /api/v1/artists/{id}
GET /api/v1/albums
GET /api/v1/albums/{id}
GET /api/v1/tracks?artist_id=<id>&album_id=<id>
GET /api/v1/tracks/{id}
GET /api/v1/search?q=<query>
```

Artist, album, track, and FTS search repositories provide bounded keyset pagination and deterministic ordering. Track browse cursors are bound to their artist/album filter scope; search cursors are bound to the normalized query. Reusing either cursor with different inputs is rejected. The HTTP handlers below require a valid device bearer token.

Collection responses use this shape:

```json
{
  "items": [],
  "has_more": false,
  "next_cursor": "omitted when there is no next page"
}
```

`GET /api/v1/tracks` accepts optional `artist_id` and `album_id` filters. Unfiltered and artist-filtered results are ordered by title and opaque ID. Album-filtered results use disc number, track number, title, and opaque ID, with missing disc/track positions placed last; their opaque cursors preserve that ordering. `GET /api/v1/search` requires `q` plus optional `limit` and `cursor`. Invalid IDs, cursors, limits, or search input return `400` with stable error codes; missing library items return `404`.

The initial search endpoint returns track models. Search input is treated as text, never raw FTS5 syntax. Punctuation separates Unicode letter/number terms; terms become quoted prefix matches joined with `AND`. Input must be valid UTF-8 and is limited to 2,048 bytes, 16 terms, and 64 Unicode code points per term. Results rank title matches above artist, album, and genre matches, then use title and opaque ID as deterministic tie-breakers.

Detail responses expose metadata and opaque related IDs, not indexed roots or source paths. Track metadata uses lowercase `flac` or `mp3` format values and may include duration in milliseconds, sample rate, bit depth when available, channel count, genre, date, disc/track positions, and related artist/album/cover IDs. There is no playlist or saved-queue HTTP API; the Android client stores one optional queue snapshot locally and re-resolves track IDs through `GET /api/v1/tracks/{id}`.

## Artwork and streaming

```text
GET /api/v1/covers/{id}
GET /api/v1/tracks/{id}/stream
HEAD /api/v1/tracks/{id}/stream
```

Cover responses are served from the artwork cache with a validated `Content-Type` (`image/jpeg`, `image/png`, `image/gif`, or `image/webp`). `GET /api/v1/covers/{id}` requires a device bearer token, uses `http.ServeContent` for efficient delivery, and never exposes cache paths.

Stream responses serve the original FLAC or MP3 file with `audio/flac` or `audio/mpeg`, `Content-Length`, validators, `Last-Modified`, and HTTP byte-range behavior via `http.ServeContent`. `GET` and `HEAD /api/v1/tracks/{id}/stream` require a device bearer token.

Bearer tokens are accepted through headers, never query parameters. Streaming resolves the opaque track ID, repeats root-bound path validation, and revalidates opened-file identity against indexed size and modification metadata.

## Authentication and admin API

The browser administration interface at `/admin/` uses the same HttpOnly session cookie and CSRF contract as the admin JSON endpoints. Admin-only routes are separate from device routes. Device tokens are not valid admin credentials. Library-root paths are shown only in the administration UI.

## Evolution

New optional fields are preferred over breaking changes. Renaming/removing fields, changing authentication semantics, or changing pagination contracts is breaking and requires an API version decision recorded in `DECISIONS.md`.
