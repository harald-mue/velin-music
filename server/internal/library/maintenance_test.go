package library

import (
	"context"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/harald-mue/velin-music/server/internal/db"
)

func TestRecoverInterruptedScansClearsMarkersAndAllowsNewScan(t *testing.T) {
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
	if _, err := database.Exec(`
		INSERT INTO scan_seen_tracks (scan_run_id, relative_path)
		VALUES ('scan-running', 'album/track.flac')`); err != nil {
		t.Fatalf("insert scan marker: %v", err)
	}

	recovered, err := RecoverInterruptedScans(context.Background(), database)
	if err != nil {
		t.Fatalf("RecoverInterruptedScans() error = %v", err)
	}
	if recovered != 1 {
		t.Fatalf("recovered scans = %d, want 1", recovered)
	}

	var status string
	if err := database.QueryRow("SELECT status FROM scan_runs WHERE id = 'scan-running'").Scan(&status); err != nil {
		t.Fatalf("read scan status: %v", err)
	}
	if status != "failed" {
		t.Fatalf("scan status = %q, want failed", status)
	}
	var markerCount int
	if err := database.QueryRow("SELECT COUNT(*) FROM scan_seen_tracks").Scan(&markerCount); err != nil {
		t.Fatalf("count scan markers: %v", err)
	}
	if markerCount != 0 {
		t.Fatalf("marker count = %d, want 0", markerCount)
	}
	var errorCode string
	if err := database.QueryRow("SELECT error_code FROM scan_errors WHERE scan_run_id = 'scan-running'").Scan(&errorCode); err != nil {
		t.Fatalf("read scan error: %v", err)
	}
	if errorCode != "interrupted" {
		t.Fatalf("scan error code = %q, want interrupted", errorCode)
	}

	scan, err := NewTrackRepository(database).BeginScan(context.Background(), root.ID)
	if err != nil {
		t.Fatalf("BeginScan() after recovery error = %v", err)
	}
	if err := scan.Fail(context.Background(), context.Canceled); err == nil {
		t.Fatal("Fail() error = nil")
	}
}

func TestArtworkCacheGarbageCollectRemovesUnreferencedFiles(t *testing.T) {
	dataDir := t.TempDir()
	cache, err := NewArtworkCache(dataDir)
	if err != nil {
		t.Fatalf("NewArtworkCache() error = %v", err)
	}
	database := openLibraryTestDB(t)
	store := NewCoverStore(database, cache)

	cover, err := store.Store(context.Background(), testPNGImage(1, 1), "image/png")
	if err != nil {
		t.Fatalf("Store() error = %v", err)
	}
	if _, err := os.Stat(cover.CachePath); err != nil {
		t.Fatalf("cached file missing: %v", err)
	}

	if _, err := database.Exec("DELETE FROM covers WHERE id = ?", cover.ID); err != nil {
		t.Fatalf("delete cover row: %v", err)
	}

	removed, err := cache.GarbageCollect(context.Background(), database)
	if err != nil {
		t.Fatalf("GarbageCollect() error = %v", err)
	}
	if removed != 1 {
		t.Fatalf("removed files = %d, want 1", removed)
	}
	if _, err := os.Stat(cover.CachePath); err == nil {
		t.Fatal("unreferenced cache file still exists")
	}
}

func TestArtworkCacheGarbageCollectRetainsReferencedFiles(t *testing.T) {
	dataDir := t.TempDir()
	cache, err := NewArtworkCache(dataDir)
	if err != nil {
		t.Fatalf("NewArtworkCache() error = %v", err)
	}
	database := openLibraryTestDB(t)
	store := NewCoverStore(database, cache)

	cover, err := store.Store(context.Background(), testPNGImage(1, 1), "image/png")
	if err != nil {
		t.Fatalf("Store() error = %v", err)
	}

	removed, err := cache.GarbageCollect(context.Background(), database)
	if err != nil {
		t.Fatalf("GarbageCollect() error = %v", err)
	}
	if removed != 0 {
		t.Fatalf("removed files = %d, want 0", removed)
	}
	if _, err := os.Stat(cover.CachePath); err != nil {
		t.Fatalf("referenced cache file missing: %v", err)
	}
}

func TestRunStartupMaintenanceRecoversAndCollects(t *testing.T) {
	database, err := db.Open(context.Background(), filepath.Join(t.TempDir(), "velin.db"))
	if err != nil {
		t.Fatalf("open database: %v", err)
	}
	t.Cleanup(func() { _ = database.Close() })

	dataDir := t.TempDir()
	cache, err := NewArtworkCache(dataDir)
	if err != nil {
		t.Fatalf("NewArtworkCache() error = %v", err)
	}
	store := NewCoverStore(database, cache)
	cover, err := store.Store(context.Background(), testPNGImage(1, 1), "image/png")
	if err != nil {
		t.Fatalf("Store() error = %v", err)
	}
	if _, err := database.Exec("DELETE FROM covers WHERE id = ?", cover.ID); err != nil {
		t.Fatalf("delete cover row: %v", err)
	}

	root, err := NewStore(database).AddRoot(context.Background(), t.TempDir())
	if err != nil {
		t.Fatalf("AddRoot() error = %v", err)
	}
	now := time.Now().UTC().Format(time.RFC3339Nano)
	if _, err := database.Exec(`
		INSERT INTO scan_runs (id, root_id, status, started_at)
		VALUES ('scan-running', ?, 'running', ?)`, root.ID, now); err != nil {
		t.Fatalf("insert running scan: %v", err)
	}

	result, err := RunStartupMaintenance(context.Background(), database, cache)
	if err != nil {
		t.Fatalf("RunStartupMaintenance() error = %v", err)
	}
	if result.RecoveredScans != 1 || result.RemovedCacheFiles != 1 {
		t.Fatalf("maintenance result = %+v", result)
	}
}
