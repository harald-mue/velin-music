package library

import (
	"context"
	"errors"
	"path/filepath"
	"testing"
	"time"

	"github.com/harald-mue/velin-music/server/internal/db"
)

func TestScanQueryRepositoryListRecentAndLatest(t *testing.T) {
	database, err := db.Open(context.Background(), filepath.Join(t.TempDir(), "velin.db"))
	if err != nil {
		t.Fatalf("open database: %v", err)
	}
	t.Cleanup(func() { _ = database.Close() })

	rootDir := t.TempDir()
	store := NewStore(database)
	root, err := store.AddRoot(context.Background(), rootDir)
	if err != nil {
		t.Fatalf("AddRoot() error = %v", err)
	}

	now := time.Now().UTC().Format(time.RFC3339Nano)
	if _, err := database.Exec(`
		INSERT INTO scan_runs (id, root_id, status, started_at, finished_at, files_seen, files_indexed, files_removed)
		VALUES ('scan-1', ?, 'completed', ?, ?, 3, 2, 1)`, root.ID, now, now); err != nil {
		t.Fatalf("insert scan run: %v", err)
	}

	repo := NewScanQueryRepository(database)
	latest, err := repo.LatestForRoot(context.Background(), root.ID)
	if err != nil {
		t.Fatalf("LatestForRoot() error = %v", err)
	}
	if latest == nil || latest.Status != "completed" || latest.FilesSeen != 3 {
		t.Fatalf("latest scan = %+v", latest)
	}
	runs, err := repo.ListRecent(context.Background(), 5)
	if err != nil {
		t.Fatalf("ListRecent() error = %v", err)
	}
	if len(runs) != 1 || runs[0].ID != "scan-1" {
		t.Fatalf("recent scans = %+v", runs)
	}
}

func TestScanQueryRepositoryGetByIDAndErrors(t *testing.T) {
	database, err := db.Open(context.Background(), filepath.Join(t.TempDir(), "velin.db"))
	if err != nil {
		t.Fatalf("open database: %v", err)
	}
	t.Cleanup(func() { _ = database.Close() })

	rootDir := t.TempDir()
	store := NewStore(database)
	root, err := store.AddRoot(context.Background(), rootDir)
	if err != nil {
		t.Fatalf("AddRoot() error = %v", err)
	}

	now := time.Now().UTC().Format(time.RFC3339Nano)
	if _, err := database.Exec(`
		INSERT INTO scan_runs (id, root_id, status, started_at)
		VALUES ('scan-1', ?, 'failed', ?)`, root.ID, now); err != nil {
		t.Fatalf("insert scan run: %v", err)
	}
	if _, err := database.Exec(`
		INSERT INTO scan_errors (scan_run_id, root_id, source_name, error_code, message, created_at)
		VALUES ('scan-1', ?, 'album/track.flac', 'metadata', 'invalid metadata', ?)`, root.ID, now); err != nil {
		t.Fatalf("insert scan error: %v", err)
	}

	repo := NewScanQueryRepository(database)
	run, err := repo.GetByID(context.Background(), "scan-1")
	if err != nil {
		t.Fatalf("GetByID() error = %v", err)
	}
	if run.Status != "failed" {
		t.Fatalf("scan status = %q", run.Status)
	}
	total, err := repo.CountErrors(context.Background(), "scan-1")
	if err != nil || total != 1 {
		t.Fatalf("CountErrors() = %d, %v", total, err)
	}
	errorsList, err := repo.ListErrors(context.Background(), "scan-1", 10)
	if err != nil || len(errorsList) != 1 || errorsList[0].SourceName != "album/track.flac" {
		t.Fatalf("ListErrors() = %+v, %v", errorsList, err)
	}
	if _, err := repo.GetByID(context.Background(), "missing"); !errors.Is(err, ErrScanRunNotFound) {
		t.Fatalf("GetByID(missing) error = %v", err)
	}
}

func TestScanServiceStartRootRejectsRunningScan(t *testing.T) {
	database, err := db.Open(context.Background(), filepath.Join(t.TempDir(), "velin.db"))
	if err != nil {
		t.Fatalf("open database: %v", err)
	}
	t.Cleanup(func() { _ = database.Close() })

	rootDir := t.TempDir()
	store := NewStore(database)
	root, err := store.AddRoot(context.Background(), rootDir)
	if err != nil {
		t.Fatalf("AddRoot() error = %v", err)
	}

	now := time.Now().UTC().Format(time.RFC3339Nano)
	if _, err := database.Exec(`
		INSERT INTO scan_runs (id, root_id, status, started_at)
		VALUES ('scan-running', ?, 'running', ?)`, root.ID, now); err != nil {
		t.Fatalf("insert running scan: %v", err)
	}

	queries := NewScanQueryRepository(database)
	service := NewScanService(NewScanner(store, NewTrackRepository(database)), store, queries)
	if err := service.StartRoot(root.ID); err != ErrScanAlreadyRunning {
		t.Fatalf("StartRoot() error = %v, want %v", err, ErrScanAlreadyRunning)
	}
}
