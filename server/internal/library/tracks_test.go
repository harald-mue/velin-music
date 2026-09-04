package library

import (
	"context"
	"database/sql"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestTrackRepositoryUpsertsAndReconciles(t *testing.T) {
	database := openLibraryTestDB(t)
	rootID := addTestRoot(t, database, filepath.Join(t.TempDir(), "music"))
	repository := NewTrackRepository(database)
	ctx := context.Background()

	scan, err := repository.BeginScan(ctx, rootID)
	if err != nil {
		t.Fatalf("BeginScan() error = %v", err)
	}
	if _, err := repository.BeginScan(ctx, rootID); err == nil {
		t.Fatal("concurrent BeginScan() error = nil, want error")
	}
	first := MediaFile{RelativePath: "album/track.flac", Format: FormatFLAC, Size: 100, ModifiedAt: time.Unix(10, 0)}
	firstMetadata := TrackMetadata{
		Format: FormatFLAC, Title: "First title", Artist: "Artist", AlbumArtist: "Artist",
		Album: "Album", Genre: "Ambient", Year: 2024, TrackNumber: 1, TotalTracks: 2,
		Duration: 2 * time.Minute, SampleRate: 96000, BitsPerSample: 24, Channels: 2,
	}
	if err := scan.Upsert(ctx, first, firstMetadata); err != nil {
		t.Fatalf("first Upsert() error = %v", err)
	}
	second := MediaFile{RelativePath: "album/second.mp3", Format: FormatMP3, Size: 200, ModifiedAt: time.Unix(20, 0)}
	secondMetadata := TrackMetadata{Format: FormatMP3, Title: "Second title", Artist: "Other artist", Duration: time.Minute, SampleRate: 44100, Channels: 2}
	if err := scan.Upsert(ctx, second, secondMetadata); err != nil {
		t.Fatalf("second Upsert() error = %v", err)
	}
	if err := scan.Finish(ctx); err != nil {
		t.Fatalf("Finish() error = %v", err)
	}

	var count int
	if err := database.QueryRow("SELECT COUNT(*) FROM tracks WHERE root_id = ?", rootID).Scan(&count); err != nil {
		t.Fatalf("count initial tracks: %v", err)
	}
	if count != 2 {
		t.Fatalf("initial track count = %d, want 2", count)
	}
	var firstID string
	if err := database.QueryRow("SELECT id FROM tracks WHERE root_id = ? AND relative_path = ?", rootID, first.RelativePath).Scan(&firstID); err != nil {
		t.Fatalf("find first track: %v", err)
	}

	updated := firstMetadata
	updated.Title = "Updated title"
	updated.Genre = "Downtempo"
	scan, err = repository.BeginScan(ctx, rootID)
	if err != nil {
		t.Fatalf("second BeginScan() error = %v", err)
	}
	first.Size = 150
	first.ModifiedAt = time.Unix(30, 0)
	if err := scan.Upsert(ctx, first, updated); err != nil {
		t.Fatalf("updated Upsert() error = %v", err)
	}
	if err := scan.Finish(ctx); err != nil {
		t.Fatalf("second Finish() error = %v", err)
	}

	if err := database.QueryRow("SELECT COUNT(*) FROM tracks WHERE root_id = ?", rootID).Scan(&count); err != nil {
		t.Fatalf("count reconciled tracks: %v", err)
	}
	if count != 1 {
		t.Fatalf("reconciled track count = %d, want 1", count)
	}
	for _, table := range []string{"artists", "albums"} {
		if err := database.QueryRow("SELECT COUNT(*) FROM " + table).Scan(&count); err != nil {
			t.Fatalf("count reconciled %s: %v", table, err)
		}
		if count != 1 {
			t.Fatalf("reconciled %s count = %d, want 1", table, count)
		}
	}
	var gotID, gotTitle, gotGenre string
	var gotSize int64
	if err := database.QueryRow(`SELECT id, title, genre, file_size FROM tracks WHERE root_id = ? AND relative_path = ?`, rootID, first.RelativePath).Scan(&gotID, &gotTitle, &gotGenre, &gotSize); err != nil {
		t.Fatalf("read updated track: %v", err)
	}
	if gotID != firstID || gotTitle != "Updated title" || gotGenre != "Downtempo" || gotSize != 150 {
		t.Fatalf("updated track = (%q, %q, %q, %d)", gotID, gotTitle, gotGenre, gotSize)
	}

	var searchCount int
	if err := database.QueryRow("SELECT COUNT(*) FROM library_fts WHERE entity_type = 'track'").Scan(&searchCount); err != nil {
		t.Fatalf("count track search entries: %v", err)
	}
	if searchCount != 1 {
		t.Fatalf("track search entry count = %d, want 1", searchCount)
	}
	if err := NewStore(database).RemoveRoot(ctx, rootID); err != nil {
		t.Fatalf("remove root: %v", err)
	}
	if err := database.QueryRow("SELECT COUNT(*) FROM library_fts WHERE entity_type = 'track'").Scan(&searchCount); err != nil {
		t.Fatalf("count search entries after root removal: %v", err)
	}
	if searchCount != 0 {
		t.Fatalf("search entries after root removal = %d, want 0", searchCount)
	}
	for _, table := range []string{"artists", "albums", "covers"} {
		if err := database.QueryRow("SELECT COUNT(*) FROM " + table).Scan(&count); err != nil {
			t.Fatalf("count %s after root removal: %v", table, err)
		}
		if count != 0 {
			t.Fatalf("%s count after root removal = %d, want 0", table, count)
		}
	}
}

func TestTrackRepositoryRetainsUnchangedTrackWithoutUpsert(t *testing.T) {
	database := openLibraryTestDB(t)
	rootID := addTestRoot(t, database, filepath.Join(t.TempDir(), "music"))
	repository := NewTrackRepository(database)
	ctx := context.Background()
	media := MediaFile{RelativePath: "unchanged.flac", Format: FormatFLAC, Size: 42, ModifiedAt: time.Unix(100, 20)}

	scan, err := repository.BeginScan(ctx, rootID)
	if err != nil {
		t.Fatalf("BeginScan() error = %v", err)
	}
	if err := scan.Upsert(ctx, media, TrackMetadata{Format: FormatFLAC, Title: "Unchanged"}); err != nil {
		t.Fatalf("Upsert() error = %v", err)
	}
	if err := scan.Finish(ctx); err != nil {
		t.Fatalf("Finish() error = %v", err)
	}

	scan, err = repository.BeginScan(ctx, rootID)
	if err != nil {
		t.Fatalf("second BeginScan() error = %v", err)
	}
	unchanged, err := scan.RetainUnchanged(ctx, media)
	if err != nil || !unchanged {
		t.Fatalf("RetainUnchanged() = %v, error %v", unchanged, err)
	}
	changed := media
	changed.Size++
	unchanged, err = scan.RetainUnchanged(ctx, changed)
	if err != nil || unchanged {
		t.Fatalf("RetainUnchanged(changed) = %v, error %v", unchanged, err)
	}
	if err := scan.Finish(ctx); err != nil {
		t.Fatalf("second Finish() error = %v", err)
	}
	var count int
	if err := database.QueryRow("SELECT COUNT(*) FROM tracks WHERE root_id = ?", rootID).Scan(&count); err != nil {
		t.Fatalf("count retained tracks: %v", err)
	}
	if count != 1 {
		t.Fatalf("retained track count = %d, want 1", count)
	}
}

func TestTrackRepositoryFailedScanDoesNotDeleteTracks(t *testing.T) {
	database := openLibraryTestDB(t)
	rootID := addTestRoot(t, database, filepath.Join(t.TempDir(), "music"))
	repository := NewTrackRepository(database)
	ctx := context.Background()

	scan, err := repository.BeginScan(ctx, rootID)
	if err != nil {
		t.Fatalf("BeginScan() error = %v", err)
	}
	media := MediaFile{RelativePath: "keep.mp3", Format: FormatMP3, Size: 10}
	if err := scan.Upsert(ctx, media, TrackMetadata{Format: FormatMP3, Title: "Keep"}); err != nil {
		t.Fatalf("Upsert() error = %v", err)
	}
	if err := scan.Finish(ctx); err != nil {
		t.Fatalf("Finish() error = %v", err)
	}

	scan, err = repository.BeginScan(ctx, rootID)
	if err != nil {
		t.Fatalf("second BeginScan() error = %v", err)
	}
	wantErr := errors.New("permission denied")
	if err := scan.Fail(ctx, wantErr); !errors.Is(err, wantErr) {
		t.Fatalf("Fail() error = %v, want %v", err, wantErr)
	}
	var count int
	if err := database.QueryRow("SELECT COUNT(*) FROM tracks WHERE root_id = ?", rootID).Scan(&count); err != nil {
		t.Fatalf("count tracks after failed scan: %v", err)
	}
	if count != 1 {
		t.Fatalf("tracks after failed scan = %d, want 1", count)
	}
	var status string
	if err := database.QueryRow("SELECT status FROM scan_runs ORDER BY started_at DESC LIMIT 1").Scan(&status); err != nil {
		t.Fatalf("read failed scan status: %v", err)
	}
	if status != "failed" {
		t.Fatalf("scan status = %q, want failed", status)
	}
}

func TestTrackRepositoryBoundsUntrustedMetadataText(t *testing.T) {
	database := openLibraryTestDB(t)
	rootID := addTestRoot(t, database, filepath.Join(t.TempDir(), "music"))
	scan, err := NewTrackRepository(database).BeginScan(context.Background(), rootID)
	if err != nil {
		t.Fatalf("BeginScan() error = %v", err)
	}
	if err := scan.Upsert(context.Background(), MediaFile{RelativePath: "long.flac", Format: FormatFLAC, Size: 1}, TrackMetadata{
		Format: FormatFLAC,
		Title:  " \n" + strings.Repeat("界", maxMetadataTextRunes+10),
		Genre:  strings.Repeat("音", maxGenreRunes+10) + "\t ",
	}); err != nil {
		t.Fatalf("Upsert() error = %v", err)
	}
	if err := scan.Finish(context.Background()); err != nil {
		t.Fatalf("Finish() error = %v", err)
	}
	var title, genre string
	if err := database.QueryRow("SELECT title, genre FROM tracks WHERE root_id = ?", rootID).Scan(&title, &genre); err != nil {
		t.Fatalf("read bounded metadata: %v", err)
	}
	if len([]rune(title)) != maxMetadataTextRunes || len([]rune(genre)) != maxGenreRunes {
		t.Fatalf("bounded metadata lengths = title %d, genre %d", len([]rune(title)), len([]rune(genre)))
	}
	if strings.ContainsAny(title+genre, "\n\t") {
		t.Fatalf("metadata whitespace was not normalized: title %q, genre %q", title, genre)
	}
}

func TestTrackRepositoryRecordsCancelledScan(t *testing.T) {
	database := openLibraryTestDB(t)
	rootID := addTestRoot(t, database, filepath.Join(t.TempDir(), "music"))
	scan, err := NewTrackRepository(database).BeginScan(context.Background(), rootID)
	if err != nil {
		t.Fatalf("BeginScan() error = %v", err)
	}
	if err := scan.Fail(context.Background(), context.Canceled); !errors.Is(err, context.Canceled) {
		t.Fatalf("Fail(context.Canceled) error = %v", err)
	}
	var status string
	if err := database.QueryRow("SELECT status FROM scan_runs WHERE id = ?", scan.id).Scan(&status); err != nil {
		t.Fatalf("read cancelled scan: %v", err)
	}
	if status != "cancelled" {
		t.Fatalf("cancelled scan status = %q", status)
	}
}

func TestTrackRepositoryRejectsInvalidInput(t *testing.T) {
	database := openLibraryTestDB(t)
	rootID := addTestRoot(t, database, filepath.Join(t.TempDir(), "music"))
	repository := NewTrackRepository(database)
	scan, err := repository.BeginScan(context.Background(), rootID)
	if err != nil {
		t.Fatalf("BeginScan() error = %v", err)
	}
	if err := scan.Upsert(context.Background(), MediaFile{RelativePath: "../escape.mp3", Format: FormatMP3}, TrackMetadata{Format: FormatMP3}); err == nil {
		t.Fatal("Upsert() error = nil for escaping path")
	}
	if err := scan.Fail(context.Background(), errors.New("test cleanup")); err == nil {
		t.Fatal("Fail() error = nil")
	}
}

func addTestRoot(t *testing.T, database *sql.DB, path string) string {
	t.Helper()
	if err := mkdirTestRoot(path); err != nil {
		t.Fatalf("create test root: %v", err)
	}
	root, err := NewStore(database).AddRoot(context.Background(), path)
	if err != nil {
		t.Fatalf("add test root: %v", err)
	}
	return root.ID
}

func mkdirTestRoot(path string) error {
	return os.Mkdir(path, 0o755)
}
