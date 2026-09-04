// Package migrate applies ordered SQLite schema migrations.
package migrate

import (
	"context"
	"database/sql"
	"fmt"
	"sort"
	"time"
)

// Migration is one immutable schema change.
type Migration struct {
	Version int
	Name    string
	SQL     string
}

// Apply creates the migration ledger and applies all pending migrations atomically.
func Apply(ctx context.Context, db *sql.DB, migrations []Migration) error {
	ordered, err := validateMigrations(migrations)
	if err != nil {
		return err
	}

	tx, err := db.BeginTx(ctx, nil)
	if err != nil {
		return fmt.Errorf("begin migration transaction: %w", err)
	}
	defer func() { _ = tx.Rollback() }()

	if _, err := tx.ExecContext(ctx, `
		CREATE TABLE IF NOT EXISTS schema_migrations (
			version INTEGER PRIMARY KEY,
			name TEXT NOT NULL,
			applied_at TEXT NOT NULL
		)`); err != nil {
		return fmt.Errorf("create migration ledger: %w", err)
	}

	applied, err := readApplied(ctx, tx)
	if err != nil {
		return err
	}
	if err := validateApplied(ordered, applied); err != nil {
		return err
	}

	for _, migration := range ordered {
		if _, ok := applied[migration.Version]; ok {
			continue
		}

		if _, err := tx.ExecContext(ctx, migration.SQL); err != nil {
			return fmt.Errorf("apply migration %03d_%s: %w", migration.Version, migration.Name, err)
		}
		if _, err := tx.ExecContext(ctx,
			"INSERT INTO schema_migrations (version, name, applied_at) VALUES (?, ?, ?)",
			migration.Version, migration.Name, time.Now().UTC().Format(time.RFC3339Nano),
		); err != nil {
			return fmt.Errorf("record migration %03d_%s: %w", migration.Version, migration.Name, err)
		}
	}

	if err := tx.Commit(); err != nil {
		return fmt.Errorf("commit migrations: %w", err)
	}
	return nil
}

func validateMigrations(migrations []Migration) ([]Migration, error) {
	ordered := append([]Migration(nil), migrations...)
	sort.Slice(ordered, func(i, j int) bool {
		return ordered[i].Version < ordered[j].Version
	})

	for i, migration := range ordered {
		if migration.Version <= 0 {
			return nil, fmt.Errorf("migration version must be positive: %d", migration.Version)
		}
		if migration.Name == "" {
			return nil, fmt.Errorf("migration %d has no name", migration.Version)
		}
		if migration.SQL == "" {
			return nil, fmt.Errorf("migration %d_%s has no SQL", migration.Version, migration.Name)
		}
		if i == 0 && migration.Version != 1 {
			return nil, fmt.Errorf("first migration version must be 1, got %d", migration.Version)
		}
		if i > 0 && ordered[i-1].Version == migration.Version {
			return nil, fmt.Errorf("duplicate migration version: %d", migration.Version)
		}
		if i > 0 && ordered[i-1].Version+1 != migration.Version {
			return nil, fmt.Errorf("migration versions must be contiguous: %d followed by %d", ordered[i-1].Version, migration.Version)
		}
	}
	return ordered, nil
}

func readApplied(ctx context.Context, tx *sql.Tx) (map[int]string, error) {
	rows, err := tx.QueryContext(ctx, `SELECT version, name FROM schema_migrations ORDER BY version`)
	if err != nil {
		return nil, fmt.Errorf("read migration ledger: %w", err)
	}
	defer rows.Close()

	applied := make(map[int]string)
	for rows.Next() {
		var version int
		var name string
		if err := rows.Scan(&version, &name); err != nil {
			return nil, fmt.Errorf("read migration row: %w", err)
		}
		if _, exists := applied[version]; exists {
			return nil, fmt.Errorf("duplicate applied migration version: %d", version)
		}
		applied[version] = name
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("iterate migration ledger: %w", err)
	}
	return applied, nil
}

func validateApplied(migrations []Migration, applied map[int]string) error {
	expected := make(map[int]string, len(migrations))
	for _, migration := range migrations {
		expected[migration.Version] = migration.Name
	}

	maxApplied := 0
	for version, name := range applied {
		migrationName, ok := expected[version]
		if !ok {
			return fmt.Errorf("database contains unknown migration version: %d", version)
		}
		if migrationName != name {
			return fmt.Errorf("migration %d name mismatch: database has %q, code has %q", version, name, migrationName)
		}
		if version > maxApplied {
			maxApplied = version
		}
	}

	for version := 1; version <= maxApplied; version++ {
		if _, ok := applied[version]; !ok {
			return fmt.Errorf("migration ledger has a gap before version %d", maxApplied)
		}
	}
	return nil
}
