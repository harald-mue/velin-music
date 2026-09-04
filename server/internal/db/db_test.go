package db

import (
	"context"
	"database/sql"
	"os"
	"path/filepath"
	"testing"
)

func TestOpenAppliesInitialSchema(t *testing.T) {
	path := filepath.Join(t.TempDir(), "velin.db")
	ctx := context.Background()

	database, err := Open(ctx, path)
	if err != nil {
		t.Fatalf("Open() error = %v", err)
	}
	assertSchema(t, database)
	if err := database.Close(); err != nil {
		t.Fatalf("close database: %v", err)
	}

	// Opening the same database again must be safe and must not re-run migration 1.
	database, err = Open(ctx, path)
	if err != nil {
		t.Fatalf("reopen database: %v", err)
	}
	defer database.Close()

	var migrationCount int
	if err := database.QueryRowContext(ctx,
		"SELECT COUNT(*) FROM schema_migrations WHERE version = 1 AND name = 'initial_schema'").Scan(&migrationCount); err != nil {
		t.Fatalf("query migration ledger: %v", err)
	}
	if migrationCount != 1 {
		t.Fatalf("initial migration count = %d, want 1", migrationCount)
	}
	var scanMarkerCount int
	if err := database.QueryRowContext(ctx, "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'scan_seen_tracks'").Scan(&scanMarkerCount); err != nil {
		t.Fatalf("query scan marker table: %v", err)
	}
	if scanMarkerCount != 1 {
		t.Fatal("scan_seen_tracks migration was not applied")
	}
	for _, index := range []string{"albums_title_order_idx", "tracks_title_order_idx", "tracks_artist_title_order_idx", "tracks_album_title_order_idx", "scan_runs_one_running_per_root_idx"} {
		var indexCount int
		if err := database.QueryRowContext(ctx, "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = ?", index).Scan(&indexCount); err != nil {
			t.Fatalf("query index %q: %v", index, err)
		}
		if indexCount != 1 {
			t.Fatalf("index %q is missing", index)
		}
	}
}

func TestOpenRequiresExistingParentDirectory(t *testing.T) {
	path := filepath.Join(t.TempDir(), "missing", "velin.db")
	if _, err := Open(context.Background(), path); err == nil {
		t.Fatal("Open() error = nil, want missing parent error")
	}
}

func TestOpenRejectsSymlinkDatabase(t *testing.T) {
	parent := t.TempDir()
	target := filepath.Join(parent, "target.db")
	if err := os.WriteFile(target, nil, 0o600); err != nil {
		t.Fatalf("create target: %v", err)
	}
	link := filepath.Join(parent, "velin.db")
	if err := os.Symlink(target, link); err != nil {
		t.Skipf("symlinks unavailable: %v", err)
	}
	if _, err := Open(context.Background(), link); err == nil {
		t.Fatal("Open(symlink) error = nil, want error")
	}
}

func TestSchemaRejectsConcurrentRunningScansForRoot(t *testing.T) {
	database, err := Open(context.Background(), filepath.Join(t.TempDir(), "velin.db"))
	if err != nil {
		t.Fatalf("Open() error = %v", err)
	}
	defer database.Close()
	if _, err := database.Exec(`
		INSERT INTO library_roots (id, path, created_at, updated_at)
		VALUES ('root-1', '/music', 'now', 'now')`); err != nil {
		t.Fatalf("insert library root: %v", err)
	}
	if _, err := database.Exec(`INSERT INTO scan_runs (id, root_id, status, started_at) VALUES ('scan-1', 'root-1', 'running', 'now')`); err != nil {
		t.Fatalf("insert first running scan: %v", err)
	}
	if _, err := database.Exec(`INSERT INTO scan_runs (id, root_id, status, started_at) VALUES ('scan-2', 'root-1', 'running', 'now')`); err == nil {
		t.Fatal("second running scan insert error = nil")
	}
}

func TestSchemaAcceptsFLACAndMP3(t *testing.T) {
	database, err := Open(context.Background(), filepath.Join(t.TempDir(), "velin.db"))
	if err != nil {
		t.Fatalf("Open() error = %v", err)
	}
	defer database.Close()

	if _, err := database.Exec(`
		INSERT INTO library_roots (id, path, created_at, updated_at)
		VALUES ('root-1', '/music', 'now', 'now')`); err != nil {
		t.Fatalf("insert library root: %v", err)
	}
	for _, format := range []string{"flac", "mp3"} {
		_, err := database.Exec(`
			INSERT INTO tracks (
				id, root_id, relative_path, format, title, file_size,
				modified_at_ns, created_at, updated_at
			) VALUES (?, 'root-1', ?, ?, 'Test track', 1, 1, 'now', 'now')`,
			format, format+"/track."+format, format)
		if err != nil {
			t.Fatalf("insert %s track: %v", format, err)
		}
	}

	var count int
	if err := database.QueryRow("SELECT COUNT(*) FROM tracks").Scan(&count); err != nil {
		t.Fatalf("count tracks: %v", err)
	}
	if count != 2 {
		t.Fatalf("track count = %d, want 2", count)
	}
}

func assertSchema(t *testing.T, database *sql.DB) {
	t.Helper()
	ctx := context.Background()
	for _, table := range []string{
		"schema_migrations",
		"library_roots",
		"artists",
		"albums",
		"tracks",
		"covers",
		"access_tokens",
		"pairing_codes",
		"scan_runs",
		"scan_errors",
		"scan_seen_tracks",
	} {
		var name string
		err := database.QueryRowContext(ctx,
			"SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?", table).Scan(&name)
		if err != nil {
			t.Fatalf("find table %q: %v", table, err)
		}
		if name != table {
			t.Fatalf("table name = %q, want %q", name, table)
		}
	}

	var ftsTable string
	if err := database.QueryRowContext(ctx,
		"SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'library_fts'").Scan(&ftsTable); err != nil {
		t.Fatalf("find FTS table: %v", err)
	}
	if ftsTable != "library_fts" {
		t.Fatalf("FTS table name = %q", ftsTable)
	}

	var foreignKeys int
	if err := database.QueryRowContext(ctx, "PRAGMA foreign_keys").Scan(&foreignKeys); err != nil {
		t.Fatalf("read foreign_keys pragma: %v", err)
	}
	if foreignKeys != 1 {
		t.Fatalf("foreign_keys = %d, want 1", foreignKeys)
	}
}
