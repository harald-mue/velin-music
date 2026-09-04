package migrate

import (
	"context"
	"database/sql"
	"path/filepath"
	"testing"

	_ "modernc.org/sqlite"
)

func TestApplyOrdersAndRecordsMigrations(t *testing.T) {
	database := openTestDB(t)
	migrations := []Migration{
		{Version: 2, Name: "second", SQL: "CREATE TABLE second (id INTEGER PRIMARY KEY)"},
		{Version: 1, Name: "first", SQL: "CREATE TABLE first (id INTEGER PRIMARY KEY)"},
	}

	if err := Apply(context.Background(), database, migrations); err != nil {
		t.Fatalf("Apply() error = %v", err)
	}

	rows, err := database.Query("SELECT version, name FROM schema_migrations ORDER BY version")
	if err != nil {
		t.Fatalf("query ledger: %v", err)
	}
	defer rows.Close()
	for _, want := range []struct {
		version int
		name    string
	}{{1, "first"}, {2, "second"}} {
		if !rows.Next() {
			t.Fatalf("missing migration %d", want.version)
		}
		var version int
		var name string
		if err := rows.Scan(&version, &name); err != nil {
			t.Fatalf("scan ledger: %v", err)
		}
		if version != want.version || name != want.name {
			t.Fatalf("ledger row = (%d, %q), want (%d, %q)", version, name, want.version, want.name)
		}
	}
	if rows.Next() {
		t.Fatal("ledger has more rows than expected")
	}
}

func TestApplyRollsBackFailedMigration(t *testing.T) {
	database := openTestDB(t)
	migrations := []Migration{{
		Version: 1,
		Name:    "broken",
		SQL:     "CREATE TABLE broken (",
	}}

	if err := Apply(context.Background(), database, migrations); err == nil {
		t.Fatal("Apply() error = nil, want error")
	}

	var count int
	err := database.QueryRow("SELECT COUNT(*) FROM sqlite_master WHERE name = 'schema_migrations'").Scan(&count)
	if err != nil {
		t.Fatalf("query rollback state: %v", err)
	}
	if count != 0 {
		t.Fatal("migration ledger remained after failed transaction")
	}
}

func TestApplyRejectsUnknownAppliedVersion(t *testing.T) {
	database := openTestDB(t)
	if _, err := database.Exec(`CREATE TABLE schema_migrations (
		version INTEGER PRIMARY KEY,
		name TEXT NOT NULL,
		applied_at TEXT NOT NULL
	)`); err != nil {
		t.Fatalf("create ledger: %v", err)
	}
	if _, err := database.Exec("INSERT INTO schema_migrations VALUES (99, 'future', 'now')"); err != nil {
		t.Fatalf("insert future migration: %v", err)
	}

	err := Apply(context.Background(), database, []Migration{{Version: 1, Name: "first", SQL: "SELECT 1"}})
	if err == nil {
		t.Fatal("Apply() error = nil, want unknown-version error")
	}
}

func openTestDB(t *testing.T) *sql.DB {
	t.Helper()
	database, err := sql.Open("sqlite", filepath.Join(t.TempDir(), "test.db"))
	if err != nil {
		t.Fatalf("open test database: %v", err)
	}
	database.SetMaxOpenConns(1)
	t.Cleanup(func() { _ = database.Close() })
	return database
}
