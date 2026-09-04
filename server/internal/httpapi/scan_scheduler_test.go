package httpapi

import (
	"context"
	"path/filepath"
	"testing"
	"time"

	"github.com/harald-mue/velin-music/server/internal/db"
)

func TestNewStartsAndStopsScanScheduler(t *testing.T) {
	database, err := db.Open(context.Background(), filepath.Join(t.TempDir(), "velin.db"))
	if err != nil {
		t.Fatalf("open database: %v", err)
	}
	t.Cleanup(func() { _ = database.Close() })

	api, err := New(Config{
		Version:      "test",
		DataDir:      t.TempDir(),
		ScanInterval: 50 * time.Millisecond,
	}, database)
	if err != nil {
		t.Fatalf("New() error = %v", err)
	}
	api.Stop()
}
