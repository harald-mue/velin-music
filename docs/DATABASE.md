# Velin Database Design

Velin uses SQLite with FTS5 and explicit schema migrations. The database is an index and application-state store; the music files remain the authoritative audio source. No PostgreSQL, Redis, or external search service is planned.

## Initial schema entities

- `library_roots`: configured, validated roots with stable IDs. Scan scheduling is controlled by process environment variables, not database settings.
- `artists`: canonical display names and stable IDs.
- `albums`: album and album-artist metadata, year, and cover reference.
- `tracks`: title, artist/album relationships, format, duration, track/disc positions, audio properties, source file identity, and cover reference.
- `covers`: cached artwork MIME type, internal cache location, byte size, and checksum. Dimensions are validated but not persisted.
- `access_tokens`: per-device token hash, name, creation/last-used timestamps, and revocation state.
- `pairing_codes`: hashed one-time secret, expiry, consumed timestamp, and requested device context.
- `admin_users`: bootstrap administrator username and Argon2id password hash.
- `admin_sessions`: hashed browser session token, CSRF token, expiry, and revocation state.
- `scan_runs`: lifecycle, throttled live counters, finalized counts, start/end timestamps, and status.
- `scan_errors`: scan run, root, source identity, safe error code/message, and timestamp.
- `scan_seen_tracks`: temporary per-scan root-relative paths used to make complete-scan deletion safe without retaining all paths in memory.

The baseline tables and FTS5 table are created by `server/migrations/001_initial_schema.sql`; scan reconciliation state is added by `002_scan_seen_tracks.sql`; browse ordering and relationship indexes are added by `003_query_indexes.sql`; the single-running-scan invariant is enforced by `004_running_scan_guard.sql`; administrator credentials and sessions are added by `005_admin_auth.sql`. Future schema changes must use new immutable migrations. Internal browse and FTS search repositories provide bounded keyset pagination; protected library HTTP handlers remain planned.

The Android client’s one saved playback queue is a private on-device JSON file, not a SQLite table and not a server playlist.

## Relationships and constraints

- Every track belongs to one library root and has a server-generated opaque ID.
- An album may have many tracks; an artist may have many albums/tracks.
- A track's source identity includes normalized root-relative path plus file size and modification metadata, but that path is never returned by the device API.
- Persisted title, artist, and album fields have collapsed whitespace and are limited to 1,024 Unicode code points; genre is limited to 256.
- Unique constraints prevent duplicate source files within a root and stabilize reconciliation.
- Foreign keys are enabled. Deleting a root through the repository cascades tracks and scan state, removes matching FTS rows, and then removes unreferenced album, artist, and cover records. Cached artwork files are garbage-collected at server startup.
- Database writes must never write, rename, or delete the corresponding music files.

## Indexes and FTS5

Existing indexes support root/source lookup, album and artist browse order, track relationships, modification reconciliation, token hash lookup, pairing expiry, scan status, and the one-running-scan-per-root constraint. The `library_fts` virtual table stores one row per indexed track containing user-facing title, artist, album, and genre text. Search compiles bounded user text into quoted Unicode token-prefix expressions joined with `AND`, then orders by weighted BM25 rank (title, artist, album, genre), title, and opaque track ID. Rank/query-bound keyset cursors avoid offsets. Browse and search repositories resolve rows to public track models; FTS content never includes filesystem paths or secrets.

## Artwork

Embedded artwork is extracted during indexing into a private managed cache, not written back to source files. JPEG, PNG, GIF, and WebP headers, MIME consistency, dimensions, pixel count, and the 8 MiB size limit are validated. Files use SHA-256 content-addressed names, and existing entries are checksum-verified before reuse. Album covers are derived from indexed track covers during reconciliation. Unreferenced cover rows are removed during reconciliation, and their cache files are garbage-collected at startup.

## Migration strategy

Migrations are ordered, immutable, and applied in one transaction where SQLite permits. The server records applied versions in a migration table and refuses ambiguous or partially unknown states. Migration files currently ship with the executable using `go:embed`, and startup fails clearly if a migration cannot be applied. Backups and downgrade policy must be documented before production schema changes.

## Reconciliation

A scan compares root-relative format, size, and modification identity. During discovery it checkpoints monotonic in-memory counters to `scan_runs` at a bounded cadence so administration clients can observe progress without counting the potentially huge `scan_seen_tracks` table or retaining paths in memory. Unchanged files receive only a seen marker; new or changed files are parsed and upserted. Files no longer present are removed only during successful reconciliation, followed by orphan metadata cleanup. A failed, cancelled, or interrupted scan never treats unvisited files as deleted. Scan runs and errors retain partial-outcome diagnostics. Track upserts record a seen marker in the same transaction as the track and search-index update. Successful `Finish` removes unmarked tracks and clears markers; explicit failure/cancellation clears markers without deleting tracks. A process crash can leave a `running` row and markers; server startup maintenance marks those scans failed, records an interrupted error, and clears markers.
