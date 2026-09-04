# Velin Security Model

Velin is intended primarily for self-hosting on a trusted private network. Plain HTTP may be supported for a trusted LAN in v1. Public internet exposure requires HTTPS, either directly or through a TLS reverse proxy. The server should remain straightforward to deploy behind such a proxy.

## Assets

- local music and embedded metadata/artwork
- server filesystem access and configured library roots
- administrator credentials and browser sessions
- Android device bearer tokens
- short-lived pairing codes
- database and artwork cache

## Trust boundaries

- The admin browser is an untrusted HTTP client.
- The Android device is a separate authenticated client and may be lost or compromised.
- The home network is not a complete security boundary.
- The server may read configured roots but must not write music files.
- Configured roots are the boundary for every file operation.

## Requirements

### Filesystem safety

- Never accept arbitrary paths from API clients.
- Resolve tracks only from opaque database IDs.
- Validate configured roots, reject files outside roots, and handle symlinks explicitly. The current implementation rejects symlink roots, canonicalizes accepted roots, skips symlinked files/directories during discovery, rechecks opened files against discovery identity before parsing, and repeats equivalent root/identity checks when opening streams.
- Never expose absolute paths or root-relative source paths through the device API, ordinary logs, or public errors. Root-relative `scan_errors.source_name` values are internal administrative diagnostics and must be shown only to an authenticated administrator, if they are exposed at all.
- Open only regular supported audio files under an approved root.
- Reject symlink endpoints for the managed data directory, cover-cache directory, and SQLite database file. Managed directories must not be accessible to group or other users.
- Treat FLAC and MP3 metadata as untrusted input; tag and FLAC metadata reads are bounded to 8 MiB, persisted text fields are length-limited, and artwork storage validates signatures, MIME consistency, maximum byte/pixel dimensions, and format headers before writing to the managed cache. Existing content-addressed cache entries are checksum-verified before reuse.
- Never pass client search text through as raw FTS5 syntax. Search input is byte/term/rune bounded and compiled only from Unicode letters, numbers, and combining marks into server-generated quoted prefix expressions.
- Velin is read-only over users' music files.

### Secrets and authentication

- Use cryptographically secure random pairing codes and at least 256 bits of entropy for permanent device tokens.
- Store permanent bearer tokens only as a slow, salted hash server-side; show/return the plaintext token only at issuance.
- Pairing codes are short-lived (approximately five minutes), single-use, stored hashed, and independently rate-limited.
- Support per-device revocation and update last-used timestamps without logging tokens.
- Use secure password hashing for admin credentials (Argon2id or another reviewed, maintained choice).
- Use HttpOnly, SameSite cookies for admin sessions; set Secure when operating over TLS.
- Protect browser mutations with CSRF tokens.
- Apply rate limiting and safe failure responses to login, pairing, and token-sensitive endpoints.
- Never put a bearer token in a URL, QR code, database log, or ordinary application log.
- Android pairing QR payloads accept exactly `server_url` and one-time `code`; unexpected fields (including any token field), oversized payloads, malformed URLs, and malformed codes are rejected. Camera permission is requested only when scanning starts and manual pairing remains available.
- The Android client encrypts device credentials with AES-GCM using a non-exportable key held by Android Keystore before writing ciphertext and IV to private app preferences. Corrupt or undecryptable stored credentials are discarded without logging their contents.
- The authenticated Android OkHttp client injects the bearer header in-memory, installs no HTTP logging interceptor, bounds JSON response bytes and page sizes, validates public model fields, and maps `401`/`403` to re-pairing without exposing the token.
- The Android playback service is non-exported. Its Media3 OkHttp data source reads credentials from private Keystore-backed storage, adds authorization headers in memory, rejects non-stream paths and origins other than the paired server, and disables redirects. Controller-provided media URIs never contain tokens.

### Transport and deployment

Plain HTTP is acceptable only for a trusted private LAN and must be clearly labeled as transport-unencrypted. The Android client permits HTTP for this use case but shows a warning during pairing. Public or hostile-network deployment requires HTTPS and secure proxy header configuration. Do not trust forwarded headers unless the proxy is explicitly configured as trusted.

### Logging and errors

Use structured logs without secrets, credentials, pairing codes, authorization headers, or local paths. Current startup failures are intentionally logged without wrapped filesystem errors. Keep client errors stable and intentionally vague where detail could disclose filesystem state. Make administrative audit events useful without recording secret material.

## Threats to test

Current tests cover path traversal, discovery/root/cache/data/database symlink endpoints, malformed metadata and artwork, cache checksum mismatch, file changes after discovery, source-file immutability, bounded literal FTS query construction, bearer-token rejection, pairing exchange, pairing rate limiting, administrator bootstrap/login/session/CSRF rejection, admin device/pairing management, server-rendered admin setup/login/pairing flows, library HTTP authorization, cover cache-bound serving, authenticated byte-range streaming, Android server-URL rejection/normalization, strict Android pairing-payload parsing, bearer-header injection, base-path preservation, bounded model decoding, authentication-failure mapping, paired-origin-only Media3 stream and artwork access, token-free media metadata, and redirect rejection. Future security tests must add invalid/expired/reused pairing codes through HTTP, Android Keystore instrumentation on a physical device or emulator, and concurrent streaming stress cases. Dependency and parser updates require review because media files are attacker-controlled input even on a private server.
