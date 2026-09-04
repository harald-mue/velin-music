-- Enforce the one-running-scan-per-root invariant inside SQLite.

CREATE UNIQUE INDEX scan_runs_one_running_per_root_idx
    ON scan_runs(root_id)
    WHERE status = 'running';
