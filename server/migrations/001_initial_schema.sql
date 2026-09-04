-- Velin's initial application schema. The migration ledger is owned by the runner.

CREATE TABLE library_roots (
    id TEXT PRIMARY KEY NOT NULL,
    path TEXT NOT NULL UNIQUE,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE artists (
    id TEXT PRIMARY KEY NOT NULL,
    name TEXT NOT NULL,
    normalized_name TEXT NOT NULL UNIQUE
);

CREATE TABLE covers (
    id TEXT PRIMARY KEY NOT NULL,
    mime_type TEXT NOT NULL,
    cache_path TEXT NOT NULL UNIQUE,
    byte_size INTEGER NOT NULL CHECK (byte_size >= 0),
    checksum TEXT NOT NULL UNIQUE,
    created_at TEXT NOT NULL
);

CREATE TABLE albums (
    id TEXT PRIMARY KEY NOT NULL,
    title TEXT NOT NULL,
    album_artist_id TEXT REFERENCES artists(id) ON DELETE SET NULL,
    year INTEGER,
    cover_id TEXT REFERENCES covers(id) ON DELETE SET NULL
);

CREATE TABLE tracks (
    id TEXT PRIMARY KEY NOT NULL,
    root_id TEXT NOT NULL REFERENCES library_roots(id) ON DELETE CASCADE,
    relative_path TEXT NOT NULL,
    format TEXT NOT NULL CHECK (format IN ('flac', 'mp3')),
    title TEXT NOT NULL,
    artist_id TEXT REFERENCES artists(id) ON DELETE SET NULL,
    album_id TEXT REFERENCES albums(id) ON DELETE SET NULL,
    album_artist_id TEXT REFERENCES artists(id) ON DELETE SET NULL,
    genre TEXT,
    date_text TEXT,
    track_number INTEGER,
    total_tracks INTEGER,
    disc_number INTEGER,
    total_discs INTEGER,
    duration_ms INTEGER CHECK (duration_ms IS NULL OR duration_ms >= 0),
    sample_rate INTEGER CHECK (sample_rate IS NULL OR sample_rate > 0),
    bits_per_sample INTEGER CHECK (bits_per_sample IS NULL OR bits_per_sample > 0),
    channels INTEGER CHECK (channels IS NULL OR channels > 0),
    cover_id TEXT REFERENCES covers(id) ON DELETE SET NULL,
    file_size INTEGER NOT NULL CHECK (file_size >= 0),
    modified_at_ns INTEGER NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    UNIQUE (root_id, relative_path)
);

CREATE TABLE access_tokens (
    id TEXT PRIMARY KEY NOT NULL,
    device_name TEXT NOT NULL,
    token_hash BLOB NOT NULL UNIQUE,
    created_at TEXT NOT NULL,
    last_used_at TEXT,
    revoked_at TEXT
);

CREATE TABLE pairing_codes (
    id TEXT PRIMARY KEY NOT NULL,
    code_hash BLOB NOT NULL UNIQUE,
    device_name TEXT NOT NULL,
    created_at TEXT NOT NULL,
    expires_at TEXT NOT NULL,
    consumed_at TEXT
);

CREATE TABLE scan_runs (
    id TEXT PRIMARY KEY NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('running', 'completed', 'failed', 'cancelled')),
    started_at TEXT NOT NULL,
    finished_at TEXT,
    files_seen INTEGER NOT NULL DEFAULT 0 CHECK (files_seen >= 0),
    files_indexed INTEGER NOT NULL DEFAULT 0 CHECK (files_indexed >= 0),
    files_removed INTEGER NOT NULL DEFAULT 0 CHECK (files_removed >= 0)
);

CREATE TABLE scan_errors (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    scan_run_id TEXT NOT NULL REFERENCES scan_runs(id) ON DELETE CASCADE,
    root_id TEXT REFERENCES library_roots(id) ON DELETE SET NULL,
    source_name TEXT,
    error_code TEXT NOT NULL,
    message TEXT NOT NULL,
    created_at TEXT NOT NULL
);

CREATE INDEX tracks_root_id_idx ON tracks(root_id);
CREATE INDEX tracks_artist_id_idx ON tracks(artist_id);
CREATE INDEX tracks_album_id_idx ON tracks(album_id);
CREATE INDEX tracks_album_artist_id_idx ON tracks(album_artist_id);
CREATE INDEX tracks_updated_at_idx ON tracks(updated_at);
CREATE INDEX pairing_codes_expires_at_idx ON pairing_codes(expires_at);
CREATE INDEX pairing_codes_consumed_at_idx ON pairing_codes(consumed_at);
CREATE INDEX scan_errors_scan_run_id_idx ON scan_errors(scan_run_id);

-- Content is maintained by the library repository as entities are indexed.
CREATE VIRTUAL TABLE library_fts USING fts5(
    entity_type UNINDEXED,
    entity_id UNINDEXED,
    title,
    artist,
    album,
    genre
);
