// Package migrations contains the schema migrations shipped with Velin.
package migrations

import (
	"embed"
	"fmt"

	"github.com/harald-mue/velin-music/server/internal/db/migrate"
)

//go:embed 001_initial_schema.sql 002_scan_seen_tracks.sql 003_query_indexes.sql 004_running_scan_guard.sql 005_admin_auth.sql
var files embed.FS

// All returns the migrations supported by this server version.
func All() []migrate.Migration {
	return []migrate.Migration{
		{
			Version: 1,
			Name:    "initial_schema",
			SQL:     mustRead("001_initial_schema.sql"),
		},
		{
			Version: 2,
			Name:    "scan_seen_tracks",
			SQL:     mustRead("002_scan_seen_tracks.sql"),
		},
		{
			Version: 3,
			Name:    "query_indexes",
			SQL:     mustRead("003_query_indexes.sql"),
		},
		{
			Version: 4,
			Name:    "running_scan_guard",
			SQL:     mustRead("004_running_scan_guard.sql"),
		},
		{
			Version: 5,
			Name:    "admin_auth",
			SQL:     mustRead("005_admin_auth.sql"),
		},
	}
}

func mustRead(name string) string {
	contents, err := files.ReadFile(name)
	if err != nil {
		panic(fmt.Sprintf("read embedded migration %s: %v", name, err))
	}
	return string(contents)
}
