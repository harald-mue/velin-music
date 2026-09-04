-- Track paths seen during a scan are temporary reconciliation state.

ALTER TABLE scan_runs ADD COLUMN root_id TEXT REFERENCES library_roots(id) ON DELETE CASCADE;

CREATE INDEX scan_runs_root_id_idx ON scan_runs(root_id);

CREATE TABLE scan_seen_tracks (
    scan_run_id TEXT NOT NULL REFERENCES scan_runs(id) ON DELETE CASCADE,
    relative_path TEXT NOT NULL,
    PRIMARY KEY (scan_run_id, relative_path)
);
