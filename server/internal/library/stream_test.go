package library

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"testing"
)

func TestTrackStreamerOpensIndexedFLAC(t *testing.T) {
	database := openLibraryTestDB(t)
	rootPath := filepath.Join(t.TempDir(), "music")
	rootID := addTestRoot(t, database, rootPath)
	relative := "album/track.flac"
	path := filepath.Join(rootPath, filepath.FromSlash(relative))
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatalf("create album directory: %v", err)
	}
	contents := testFLAC()
	if err := os.WriteFile(path, contents, 0o600); err != nil {
		t.Fatalf("write FLAC: %v", err)
	}
	info, err := os.Stat(path)
	if err != nil {
		t.Fatalf("stat FLAC: %v", err)
	}

	scan, err := NewTrackRepository(database).BeginScan(context.Background(), rootID)
	if err != nil {
		t.Fatalf("BeginScan() error = %v", err)
	}
	if err := scan.Upsert(context.Background(), MediaFile{
		RelativePath: relative,
		Format:       FormatFLAC,
		Size:         info.Size(),
		ModifiedAt:   info.ModTime(),
	}, TrackMetadata{
		Format: FormatFLAC, Title: "Track", Artist: "Artist", AlbumArtist: "Artist", Album: "Album",
	}); err != nil {
		t.Fatalf("Upsert() error = %v", err)
	}
	if err := scan.Finish(context.Background()); err != nil {
		t.Fatalf("Finish() error = %v", err)
	}

	var trackID string
	if err := database.QueryRow("SELECT id FROM tracks WHERE root_id = ?", rootID).Scan(&trackID); err != nil {
		t.Fatalf("read track id: %v", err)
	}

	streamer := NewTrackStreamer(database)
	file, content, err := streamer.Open(context.Background(), trackID)
	if err != nil {
		t.Fatalf("Open() error = %v", err)
	}
	defer file.Close()
	if content.MIMEType != "audio/flac" || content.ModTime.IsZero() {
		t.Fatalf("content = %+v", content)
	}
}

func TestTrackStreamerRejectsMissingOrChangedTracks(t *testing.T) {
	database := openLibraryTestDB(t)
	streamer := NewTrackStreamer(database)
	ctx := context.Background()

	if _, _, err := streamer.Open(ctx, ""); err == nil {
		t.Fatal("Open(empty) error = nil")
	}
	if _, _, err := streamer.Open(ctx, "missing"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("Open(missing) error = %v, want ErrNotFound", err)
	}

	rootPath := filepath.Join(t.TempDir(), "music")
	rootID := addTestRoot(t, database, rootPath)
	relative := "track.flac"
	path := filepath.Join(rootPath, relative)
	if err := os.WriteFile(path, testFLAC(), 0o600); err != nil {
		t.Fatalf("write FLAC: %v", err)
	}
	info, err := os.Stat(path)
	if err != nil {
		t.Fatalf("stat FLAC: %v", err)
	}
	scan, err := NewTrackRepository(database).BeginScan(context.Background(), rootID)
	if err != nil {
		t.Fatalf("BeginScan() error = %v", err)
	}
	if err := scan.Upsert(context.Background(), MediaFile{
		RelativePath: relative,
		Format:       FormatFLAC,
		Size:         info.Size(),
		ModifiedAt:   info.ModTime(),
	}, TrackMetadata{
		Format: FormatFLAC, Title: "Track", Artist: "Artist", AlbumArtist: "Artist", Album: "Album",
	}); err != nil {
		t.Fatalf("Upsert() error = %v", err)
	}
	if err := scan.Finish(context.Background()); err != nil {
		t.Fatalf("Finish() error = %v", err)
	}
	var trackID string
	if err := database.QueryRow("SELECT id FROM tracks WHERE root_id = ?", rootID).Scan(&trackID); err != nil {
		t.Fatalf("read track id: %v", err)
	}
	if err := os.WriteFile(path, append(testFLAC(), 'x'), 0o600); err != nil {
		t.Fatalf("modify FLAC: %v", err)
	}
	if _, _, err := streamer.Open(ctx, trackID); err == nil {
		t.Fatal("Open(changed file) error = nil")
	}
}
