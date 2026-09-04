package library

import (
	"context"
	"crypto/sha256"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestScannerIndexesValidFilesAndRecordsParseErrors(t *testing.T) {
	database := openLibraryTestDB(t)
	rootPath := filepath.Join(t.TempDir(), "music")
	if err := os.Mkdir(rootPath, 0o755); err != nil {
		t.Fatalf("create root: %v", err)
	}
	validPath := filepath.Join(rootPath, "valid.mp3")
	if err := os.WriteFile(validPath, testMP3(), 0o600); err != nil {
		t.Fatalf("write valid MP3: %v", err)
	}
	brokenPath := filepath.Join(rootPath, "broken.flac")
	if err := os.WriteFile(brokenPath, []byte("broken"), 0o600); err != nil {
		t.Fatalf("write broken FLAC: %v", err)
	}
	validBefore := sha256.Sum256(testMP3())

	root, err := NewStore(database).AddRoot(context.Background(), rootPath)
	if err != nil {
		t.Fatalf("add root: %v", err)
	}
	cache, err := NewArtworkCache(t.TempDir())
	if err != nil {
		t.Fatalf("create artwork cache: %v", err)
	}
	repository := NewTrackRepositoryWithArtwork(database, NewCoverStore(database, cache))
	result, err := NewScanner(NewStore(database), repository).ScanRoot(context.Background(), root)
	if err != nil {
		t.Fatalf("ScanRoot() error = %v", err)
	}
	if result.RootID != root.ID || !result.Completed || result.FilesSeen != 2 || result.FilesIndexed != 1 || result.Errors != 1 {
		t.Fatalf("scan result = %+v", result)
	}
	var trackCount, errorCount, coverCount int
	if err := database.QueryRow("SELECT COUNT(*) FROM tracks WHERE root_id = ?", root.ID).Scan(&trackCount); err != nil {
		t.Fatalf("count tracks: %v", err)
	}
	if err := database.QueryRow("SELECT COUNT(*) FROM scan_errors WHERE root_id = ?", root.ID).Scan(&errorCount); err != nil {
		t.Fatalf("count scan errors: %v", err)
	}
	if err := database.QueryRow("SELECT COUNT(*) FROM covers").Scan(&coverCount); err != nil {
		t.Fatalf("count covers: %v", err)
	}
	if trackCount != 1 || errorCount != 1 || coverCount != 0 {
		t.Fatalf("counts = tracks %d, errors %d, covers %d", trackCount, errorCount, coverCount)
	}
	var sourceName, errorMessage string
	if err := database.QueryRow("SELECT source_name, message FROM scan_errors WHERE root_id = ?", root.ID).Scan(&sourceName, &errorMessage); err != nil {
		t.Fatalf("read scan error: %v", err)
	}
	if sourceName != "broken.flac" || strings.Contains(errorMessage, rootPath) {
		t.Fatalf("unsafe scan error = source %q, message %q", sourceName, errorMessage)
	}
	var status string
	var seen, indexed, removed int
	if err := database.QueryRow(`SELECT status, files_seen, files_indexed, files_removed FROM scan_runs WHERE id = (SELECT id FROM scan_runs ORDER BY started_at DESC LIMIT 1)`).Scan(&status, &seen, &indexed, &removed); err != nil {
		t.Fatalf("read scan status: %v", err)
	}
	if status != "completed" || seen != 2 || indexed != 1 || removed != 0 {
		t.Fatalf("scan database state = %s/%d/%d/%d", status, seen, indexed, removed)
	}
	contents, err := os.ReadFile(validPath)
	if err != nil {
		t.Fatalf("read valid source after scan: %v", err)
	}
	if sha256.Sum256(contents) != validBefore {
		t.Fatal("valid source file changed during scan")
	}
	var updatedAt string
	if err := database.QueryRow("SELECT updated_at FROM tracks WHERE root_id = ?", root.ID).Scan(&updatedAt); err != nil {
		t.Fatalf("read track update time: %v", err)
	}
	secondResult, err := NewScanner(NewStore(database), repository).ScanRoot(context.Background(), root)
	if err != nil {
		t.Fatalf("second ScanRoot() error = %v", err)
	}
	if secondResult.FilesSeen != 2 || secondResult.FilesIndexed != 1 || secondResult.Errors != 1 {
		t.Fatalf("second scan result = %+v", secondResult)
	}
	var secondUpdatedAt string
	if err := database.QueryRow("SELECT updated_at FROM tracks WHERE root_id = ?", root.ID).Scan(&secondUpdatedAt); err != nil {
		t.Fatalf("read second track update time: %v", err)
	}
	if secondUpdatedAt != updatedAt {
		t.Fatalf("unchanged track was re-upserted: %q changed to %q", updatedAt, secondUpdatedAt)
	}
}

func TestScannerFailsSafelyWhenRootDisappears(t *testing.T) {
	database := openLibraryTestDB(t)
	parent := t.TempDir()
	rootPath := filepath.Join(parent, "music")
	if err := os.Mkdir(rootPath, 0o755); err != nil {
		t.Fatalf("create root: %v", err)
	}
	root, err := NewStore(database).AddRoot(context.Background(), rootPath)
	if err != nil {
		t.Fatalf("add root: %v", err)
	}
	if err := os.Rename(rootPath, filepath.Join(parent, "moved")); err != nil {
		t.Fatalf("move root: %v", err)
	}

	result, err := NewScanner(NewStore(database), NewTrackRepository(database)).ScanRoot(context.Background(), root)
	if err == nil {
		t.Fatal("ScanRoot() error = nil, want failure")
	}
	if result.Completed {
		t.Fatal("failed scan reported Completed")
	}
	var status, message string
	if err := database.QueryRow(`SELECT scan_runs.status, scan_errors.message FROM scan_runs JOIN scan_errors ON scan_errors.scan_run_id = scan_runs.id ORDER BY scan_runs.started_at DESC LIMIT 1`).Scan(&status, &message); err != nil {
		t.Fatalf("read failed scan: %v", err)
	}
	if status != "failed" || message != "library discovery failed" {
		t.Fatalf("failed scan = status %q, message %q", status, message)
	}
}

func TestScannerScanAllContinuesAfterRootFailure(t *testing.T) {
	database := openLibraryTestDB(t)
	parent := t.TempDir()
	failedPath := filepath.Join(parent, "a-failed")
	validPath := filepath.Join(parent, "b-valid")
	for _, path := range []string{failedPath, validPath} {
		if err := os.Mkdir(path, 0o755); err != nil {
			t.Fatalf("create root: %v", err)
		}
	}
	store := NewStore(database)
	if _, err := store.AddRoot(context.Background(), failedPath); err != nil {
		t.Fatalf("add failed root: %v", err)
	}
	validRoot, err := store.AddRoot(context.Background(), validPath)
	if err != nil {
		t.Fatalf("add valid root: %v", err)
	}
	if err := os.WriteFile(filepath.Join(validPath, "track.mp3"), testMP3(), 0o600); err != nil {
		t.Fatalf("write valid track: %v", err)
	}
	if err := os.Rename(failedPath, filepath.Join(parent, "moved")); err != nil {
		t.Fatalf("move failed root: %v", err)
	}

	results, err := NewScanner(store, NewTrackRepository(database)).ScanAll(context.Background())
	if err == nil {
		t.Fatal("ScanAll() error = nil, want failed-root error")
	}
	if len(results) != 2 || results[0].Completed || !results[1].Completed || results[1].RootID != validRoot.ID {
		t.Fatalf("ScanAll() results = %+v", results)
	}
	var trackCount int
	if err := database.QueryRow("SELECT COUNT(*) FROM tracks WHERE root_id = ?", validRoot.ID).Scan(&trackCount); err != nil {
		t.Fatalf("count valid-root tracks: %v", err)
	}
	if trackCount != 1 {
		t.Fatalf("valid-root track count = %d, want 1", trackCount)
	}
}

func TestScannerScanAllHonorsCancellationBetweenRoots(t *testing.T) {
	database := openLibraryTestDB(t)
	store := NewStore(database)
	for index := 0; index < 2; index++ {
		path := filepath.Join(t.TempDir(), "music")
		if err := os.Mkdir(path, 0o755); err != nil {
			t.Fatalf("create root: %v", err)
		}
		if _, err := store.AddRoot(context.Background(), path); err != nil {
			t.Fatalf("add root: %v", err)
		}
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	results, err := NewScanner(store, NewTrackRepository(database)).ScanAll(ctx)
	if err == nil || len(results) != 0 {
		t.Fatalf("ScanAll() = results %d, error %v; want cancellation before scan", len(results), err)
	}
}
