-- Administrator credentials and browser sessions.

CREATE TABLE admin_users (
    id TEXT PRIMARY KEY NOT NULL,
    username TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    created_at TEXT NOT NULL
);

CREATE TABLE admin_sessions (
    id TEXT PRIMARY KEY NOT NULL,
    admin_user_id TEXT NOT NULL REFERENCES admin_users(id) ON DELETE CASCADE,
    token_hash BLOB NOT NULL UNIQUE,
    csrf_token TEXT NOT NULL,
    created_at TEXT NOT NULL,
    expires_at TEXT NOT NULL,
    revoked_at TEXT
);

CREATE INDEX admin_sessions_expires_at_idx ON admin_sessions(expires_at);
CREATE INDEX admin_sessions_admin_user_id_idx ON admin_sessions(admin_user_id);
