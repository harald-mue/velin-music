package library

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"testing"
)

func TestCoverReaderOpensIndexedCover(t *testing.T) {
	database := openLibraryTestDB(t)
	dataDir := t.TempDir()
	cache, err := NewArtworkCache(dataDir)
	if err != nil {
		t.Fatalf("NewArtworkCache() error = %v", err)
	}
	store := NewCoverStore(database, cache)
	cover, err := store.Store(context.Background(), testPNGImage(2, 2), "image/png")
	if err != nil {
		t.Fatalf("Store() error = %v", err)
	}

	reader := NewCoverReader(database, cache)
	file, content, err := reader.Open(context.Background(), cover.ID)
	if err != nil {
		t.Fatalf("Open() error = %v", err)
	}
	defer file.Close()
	if content.MIMEType != "image/png" || content.ModTime.IsZero() {
		t.Fatalf("content = %+v", content)
	}
	info, err := file.Stat()
	if err != nil || info.Size() != cover.ByteSize {
		t.Fatalf("file stat = %+v, err %v", info, err)
	}
}

func TestCoverReaderRejectsMissingOrInvalidCovers(t *testing.T) {
	dataDir := t.TempDir()
	cache, err := NewArtworkCache(dataDir)
	if err != nil {
		t.Fatalf("NewArtworkCache() error = %v", err)
	}
	reader := NewCoverReader(openLibraryTestDB(t), cache)
	ctx := context.Background()

	if _, _, err := reader.Open(ctx, ""); err == nil {
		t.Fatal("Open(empty) error = nil")
	}
	if _, _, err := reader.Open(ctx, "missing"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("Open(missing) error = %v, want ErrNotFound", err)
	}
}

func TestCoverReaderRejectsSymlinkedCacheFile(t *testing.T) {
	database := openLibraryTestDB(t)
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
	target := filepath.Join(t.TempDir(), "target.png")
	if err := os.WriteFile(target, testPNGImage(1, 1), 0o600); err != nil {
		t.Fatalf("write target: %v", err)
	}
	if err := os.Remove(cover.CachePath); err != nil {
		t.Fatalf("remove cache file: %v", err)
	}
	if err := os.Symlink(target, cover.CachePath); err != nil {
		t.Skipf("symlinks unavailable: %v", err)
	}
	if _, _, err := NewCoverReader(database, cache).Open(context.Background(), cover.ID); err == nil {
		t.Fatal("Open(symlink) error = nil")
	}
}
