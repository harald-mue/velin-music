// Package db owns the Velin SQLite connection and schema lifecycle.
package db

import (
	"context"
	"database/sql"
	"fmt"
	"net/url"
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

	dsn := (&url.URL{
		Scheme: "file",
		Path:   path,
		RawQuery: url.Values{
			"_pragma": {
				"foreign_keys(1)",
				"journal_mode(WAL)",
				"busy_timeout(5000)",
			},
		}.Encode(),
	}).String()
	database, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, fmt.Errorf("open SQLite database: %w", err)
	}
	// WAL allows bounded concurrent readers while scans perform short writes.
	// Connection-local PRAGMAs are part of the DSN so every pooled connection
	// receives identical safety and lock-wait settings.
	database.SetMaxOpenConns(4)
	database.SetMaxIdleConns(4)

	closeOnError := func(err error) (*sql.DB, error) {
		_ = database.Close()
		return nil, err
	}

	if err := database.PingContext(ctx); err != nil {
		return closeOnError(fmt.Errorf("ping SQLite database: %w", err))
	}
	if err := migrate.Apply(ctx, database, migrations.All()); err != nil {
		return closeOnError(err)
	}
	return database, nil
}
