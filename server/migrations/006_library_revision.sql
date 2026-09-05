-- Maintain a compact revision source for cache invalidation without exposing scan or path identity.
CREATE TABLE library_state (
    singleton INTEGER PRIMARY KEY NOT NULL CHECK (singleton = 1),
    revision INTEGER NOT NULL CHECK (revision >= 0)
);

INSERT INTO library_state (singleton, revision) VALUES (1, 0);

CREATE TRIGGER tracks_library_revision_insert
AFTER INSERT ON tracks
BEGIN
    UPDATE library_state SET revision = revision + 1 WHERE singleton = 1;
END;

CREATE TRIGGER tracks_library_revision_update
AFTER UPDATE ON tracks
BEGIN
    UPDATE library_state SET revision = revision + 1 WHERE singleton = 1;
END;

CREATE TRIGGER tracks_library_revision_delete
AFTER DELETE ON tracks
BEGIN
    UPDATE library_state SET revision = revision + 1 WHERE singleton = 1;
END;
