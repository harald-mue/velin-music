package library

import (
	"context"
	"errors"
	"image"
	"os"
	"path/filepath"
	"testing"
	"time"
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

func TestCoverReaderCreatesAndReusesBoundedVariant(t *testing.T) {
	database := openLibraryTestDB(t)
	cache, err := NewArtworkCache(t.TempDir())
	if err != nil {
		t.Fatalf("NewArtworkCache() error = %v", err)
	}
	cover, err := NewCoverStore(database, cache).Store(context.Background(), testPNGImage(400, 200), "image/png")
	if err != nil {
		t.Fatalf("Store() error = %v", err)
	}
	reader := NewCoverReader(database, cache)

	file, content, err := reader.OpenVariant(context.Background(), cover.ID, 128)
	if err != nil {
		t.Fatalf("OpenVariant() error = %v", err)
	}
	config, format, err := image.DecodeConfig(file)
	_ = file.Close()
	if err != nil {
		t.Fatalf("DecodeConfig() error = %v", err)
	}
	if content.MIMEType != "image/jpeg" || format != "jpeg" || config.Width != 128 || config.Height != 64 {
		t.Fatalf("variant content = %+v, format = %q, dimensions = %dx%d", content, format, config.Width, config.Height)
	}

	second, _, err := reader.OpenVariant(context.Background(), cover.ID, 128)
	if err != nil {
		t.Fatalf("second OpenVariant() error = %v", err)
	}
	_ = second.Close()
	entries, err := os.ReadDir(cache.variantDirectory)
	if err != nil || len(entries) != 1 {
		t.Fatalf("variant entries = %d, err = %v", len(entries), err)
	}
	if _, _, err := reader.OpenVariant(context.Background(), cover.ID, 1024); err == nil {
		t.Fatal("OpenVariant(1024) error = nil")
	}
}

func TestCoverReaderEnsureVariantIsIdempotent(t *testing.T) {
	database := openLibraryTestDB(t)
	cache, err := NewArtworkCache(t.TempDir())
	if err != nil {
		t.Fatalf("NewArtworkCache() error = %v", err)
	}
	cover, err := NewCoverStore(database, cache).Store(context.Background(), testPNGImage(400, 200), "image/png")
	if err != nil {
		t.Fatalf("Store() error = %v", err)
	}
	reader := NewCoverReader(database, cache)
	if err := reader.EnsureVariant(context.Background(), cover.ID, 256); err != nil {
		t.Fatalf("EnsureVariant() error = %v", err)
	}

	path := filepath.Join(cache.variantDirectory, cover.ID+"-256.jpg")
	fixedTime := time.Unix(1_700_000_000, 0)
	if err := os.Chtimes(path, fixedTime, fixedTime); err != nil {
		t.Fatalf("set variant time: %v", err)
	}
	if err := reader.EnsureVariant(context.Background(), cover.ID, 256); err != nil {
		t.Fatalf("second EnsureVariant() error = %v", err)
	}
	info, err := os.Stat(path)
	if err != nil {
		t.Fatalf("stat variant: %v", err)
	}
	if !info.ModTime().Equal(fixedTime) {
		t.Fatalf("variant was rewritten: mod time = %v, want %v", info.ModTime(), fixedTime)
	}
}

func TestCoverReaderEnsureVariantReplacesInvalidEntry(t *testing.T) {
	database := openLibraryTestDB(t)
	cache, err := NewArtworkCache(t.TempDir())
	if err != nil {
		t.Fatalf("NewArtworkCache() error = %v", err)
	}
	cover, err := NewCoverStore(database, cache).Store(context.Background(), testPNGImage(400, 200), "image/png")
	if err != nil {
		t.Fatalf("Store() error = %v", err)
	}
	path := filepath.Join(cache.variantDirectory, cover.ID+"-512.jpg")
	if err := os.WriteFile(path, []byte("invalid"), 0o600); err != nil {
		t.Fatalf("write invalid variant: %v", err)
	}

	reader := NewCoverReader(database, cache)
	if err := reader.EnsureVariant(context.Background(), cover.ID, 512); err != nil {
		t.Fatalf("EnsureVariant() error = %v", err)
	}
	file, _, err := openArtworkVariant(path, 512)
	if err != nil {
		t.Fatalf("open replaced variant: %v", err)
	}
	_ = file.Close()
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
