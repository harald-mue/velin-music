package library

import (
	"context"
	"os"
	"path/filepath"
	"testing"
)

func TestTrackRepositoryAssociatesCachedArtwork(t *testing.T) {
	database := openLibraryTestDB(t)
	rootID := addTestRoot(t, database, filepath.Join(t.TempDir(), "music"))
	cache, err := NewArtworkCache(t.TempDir())
	if err != nil {
		t.Fatalf("NewArtworkCache() error = %v", err)
	}
	repository := NewTrackRepositoryWithArtwork(database, NewCoverStore(database, cache))
	scan, err := repository.BeginScan(context.Background(), rootID)
	if err != nil {
		t.Fatalf("BeginScan() error = %v", err)
	}
	if err := scan.Upsert(context.Background(), MediaFile{
		RelativePath: "cover.flac", Format: FormatFLAC, Size: 10,
	}, TrackMetadata{
		Format: FormatFLAC, Title: "Covered track", Artist: "Artist", AlbumArtist: "Artist", Album: "Album",
		Artwork: testPNGImage(1, 1), ArtworkMIME: "image/png",
	}); err != nil {
		t.Fatalf("Upsert() error = %v", err)
	}
	if err := scan.Finish(context.Background()); err != nil {
		t.Fatalf("Finish() error = %v", err)
	}

	var coverID string
	if err := database.QueryRow("SELECT cover_id FROM tracks WHERE root_id = ?", rootID).Scan(&coverID); err != nil {
		t.Fatalf("read track cover: %v", err)
	}
	if coverID == "" {
		t.Fatal("track cover ID is empty")
	}
	var albumCoverID string
	if err := database.QueryRow("SELECT cover_id FROM albums WHERE title = 'Album'").Scan(&albumCoverID); err != nil {
		t.Fatalf("read album cover: %v", err)
	}
	if albumCoverID != coverID {
		t.Fatalf("album cover ID = %q, want track cover %q", albumCoverID, coverID)
	}
	var cachePath string
	if err := database.QueryRow("SELECT cache_path FROM covers WHERE id = ?", coverID).Scan(&cachePath); err != nil {
		t.Fatalf("read cover cache path: %v", err)
	}
	if err := NewStore(database).RemoveRoot(context.Background(), rootID); err != nil {
		t.Fatalf("remove root: %v", err)
	}
	var coverCount int
	if err := database.QueryRow("SELECT COUNT(*) FROM covers").Scan(&coverCount); err != nil {
		t.Fatalf("count covers: %v", err)
	}
	if coverCount != 0 {
		t.Fatalf("cover count after root removal = %d, want 0", coverCount)
	}
	if _, err := os.Stat(cachePath); err != nil {
		t.Fatalf("cache file removed before garbage collection: %v", err)
	}
}
