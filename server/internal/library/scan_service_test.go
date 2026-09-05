package library

import (
	"context"
	"path/filepath"
	"testing"
	"time"

	"github.com/harald-mue/velin-music/server/internal/db"
)

func openScanServiceTestDB(t *testing.T) (*ScanService, func()) {
	t.Helper()
	database, err := db.Open(context.Background(), filepath.Join(t.TempDir(), "velin.db"))
	if err != nil {
		t.Fatalf("open database: %v", err)
	}
	roots := NewStore(database)
	tracks := NewTrackRepository(database)
	scanner := NewScanner(roots, tracks)
	queries := NewScanQueryRepository(database)
	service := NewScanService(scanner, roots, queries)
	return service, func() { _ = database.Close() }
}

func TestTryStartAllSkipsOverlappingScans(t *testing.T) {
	service, cleanup := openScanServiceTestDB(t)
	defer cleanup()

	service.mu.Lock()
	service.active = true
	service.mu.Unlock()
	if service.TryStartAll() {
		t.Fatal("TryStartAll() while active = true, want false")
	}

	service.mu.Lock()
	service.active = false
	service.mu.Unlock()
	if !service.TryStartAll() {
		t.Fatal("TryStartAll() = false, want true")
	}

	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		service.mu.Lock()
		active := service.active
		service.mu.Unlock()
		if !active {
			return
		}
		time.Sleep(5 * time.Millisecond)
	}
	t.Fatal("background scan did not finish")
}

func TestStartAllReturnsConflictWhenScanRunning(t *testing.T) {
	service, cleanup := openScanServiceTestDB(t)
	defer cleanup()

	service.mu.Lock()
	service.active = true
	service.mu.Unlock()

	if err := service.StartAll(); err != ErrScanAlreadyRunning {
		t.Fatalf("StartAll() error = %v, want ErrScanAlreadyRunning", err)
	}
}
