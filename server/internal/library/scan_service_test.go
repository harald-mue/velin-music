package library

import (
	"context"
	"os"
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

func TestScanServiceTriggersAfterCompletedBatchWithoutScanLock(t *testing.T) {
	dataDir := t.TempDir()
	database, err := db.Open(context.Background(), filepath.Join(dataDir, "velin.db"))
	if err != nil {
		t.Fatalf("open database: %v", err)
	}
	t.Cleanup(func() { _ = database.Close() })
	rootPath := filepath.Join(dataDir, "music")
	if err := os.Mkdir(rootPath, 0o700); err != nil {
		t.Fatalf("create music root: %v", err)
	}
	roots := NewStore(database)
	if _, err := roots.AddRoot(context.Background(), rootPath); err != nil {
		t.Fatalf("add root: %v", err)
	}

	triggered := make(chan bool, 1)
	var service *ScanService
	service = NewScanService(
		NewScanner(roots, NewTrackRepository(database)),
		roots,
		NewScanQueryRepository(database),
		func() {
			serviceUnlocked := serviceIsInactive(service)
			triggered <- serviceUnlocked
		},
	)
	if !service.TryStartAll() {
		t.Fatal("TryStartAll() = false, want true")
	}

	select {
	case unlocked := <-triggered:
		if !unlocked {
			t.Fatal("after-batch callback ran while scan remained active")
		}
	case <-time.After(2 * time.Second):
		t.Fatal("timed out waiting for after-batch callback")
	}
}

func TestScanServiceTriggersAfterPartiallySuccessfulBatch(t *testing.T) {
	triggered := false
	service := &ScanService{afterBatch: func() { triggered = true }}
	service.notifyAfterBatch([]ScanResult{{Completed: false, FilesIndexed: 1}})
	if !triggered {
		t.Fatal("partially successful batch did not trigger callback")
	}
}

func serviceIsInactive(service *ScanService) bool {
	service.mu.Lock()
	defer service.mu.Unlock()
	return !service.active
}
