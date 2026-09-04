// Package db owns the Velin SQLite connection and schema lifecycle.
package db

import (
	"context"
	"database/sql"
	"fmt"
	"os"
	"path/filepath"

	_ "modernc.org/sqlite"

	"github.com/harald-mue/velin-music/server/internal/db/migrate"
	"github.com/harald-mue/velin-music/server/migrations"
)

// Open opens a SQLite database, applies connection settings, and runs pending migrations.
func Open(ctx context.Context, path string) (*sql.DB, error) {
	if path == "" {
		return nil, fmt.Errorf("database path must not be empty")
	}
	if dir := filepath.Dir(path); dir != "." {
		info, err := os.Stat(dir)
		if err != nil {
			return nil, fmt.Errorf("inspect database directory: %w", err)
		}
		if !info.IsDir() {
			return nil, fmt.Errorf("database parent is not a directory")
		}
	}
	if info, err := os.Lstat(path); err == nil {
		if info.Mode()&os.ModeSymlink != 0 {
			return nil, fmt.Errorf("database file must not be a symlink")
		}
		if !info.Mode().IsRegular() {
			return nil, fmt.Errorf("database path is not a regular file")
		}
	} else if !os.IsNotExist(err) {
		return nil, fmt.Errorf("inspect database file: %w", err)
	}

	database, err := sql.Open("sqlite", path)
	if err != nil {
		return nil, fmt.Errorf("open SQLite database: %w", err)
	}
	// A single connection keeps connection-local PRAGMAs deterministic and still
	// lets SQLite coordinate reads and writes safely for this self-hosted service.
	database.SetMaxOpenConns(1)
	database.SetMaxIdleConns(1)

	closeOnError := func(err error) (*sql.DB, error) {
		_ = database.Close()
		return nil, err
	}

	if err := database.PingContext(ctx); err != nil {
		return closeOnError(fmt.Errorf("ping SQLite database: %w", err))
	}
	for _, pragma := range []string{
		"PRAGMA foreign_keys = ON",
		"PRAGMA journal_mode = WAL",
		"PRAGMA busy_timeout = 5000",
	} {
		if _, err := database.ExecContext(ctx, pragma); err != nil {
			return closeOnError(fmt.Errorf("configure SQLite (%s): %w", pragma, err))
		}
	}

	if err := migrate.Apply(ctx, database, migrations.All()); err != nil {
		return closeOnError(err)
	}
	return database, nil
}
