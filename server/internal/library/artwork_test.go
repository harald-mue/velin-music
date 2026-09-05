package library

import (
	"bytes"
	"context"
	"encoding/binary"
	"image"
	"image/color"
	"image/png"
	"os"
	"path/filepath"
	"testing"
)

func TestArtworkCacheValidatesAndDeduplicatesPNG(t *testing.T) {
	cache, err := NewArtworkCache(t.TempDir())
	if err != nil {
		t.Fatalf("NewArtworkCache() error = %v", err)
	}
	data := testPNGImage(2, 3)
	cover, err := cache.Store(context.Background(), data, "image/png")
	if err != nil {
		t.Fatalf("Store() error = %v", err)
	}
	if cover.MIMEType != "image/png" || cover.ByteSize != int64(len(data)) || cover.Checksum == "" {
		t.Fatalf("cover = %+v", cover)
	}
	stored, err := os.ReadFile(cover.CachePath)
	if err != nil {
		t.Fatalf("read cached artwork: %v", err)
	}
	if !bytes.Equal(stored, data) {
		t.Fatal("cached artwork differs from source bytes")
	}
	info, err := os.Stat(cover.CachePath)
	if err != nil {
		t.Fatalf("stat cached artwork: %v", err)
	}
	if info.Mode().Perm() != 0o600 {
		t.Fatalf("cached artwork permissions = %o, want 600", info.Mode().Perm())
	}

	duplicate, err := cache.Store(context.Background(), data, "IMAGE/PNG")
	if err != nil {
		t.Fatalf("duplicate Store() error = %v", err)
	}
	if duplicate.CachePath != cover.CachePath || duplicate.Checksum != cover.Checksum {
		t.Fatalf("duplicate cover = %+v, original = %+v", duplicate, cover)
	}
}

func TestArtworkCacheRejectsInvalidOrOversizedArtwork(t *testing.T) {
	cache, err := NewArtworkCache(t.TempDir())
	if err != nil {
		t.Fatalf("NewArtworkCache() error = %v", err)
	}
	data := testPNGImage(1, 1)
	if _, err := cache.Store(context.Background(), data, "image/jpeg"); err == nil {
		t.Fatal("Store() error = nil for mismatched MIME")
	}
	if _, err := cache.Store(context.Background(), []byte("not an image"), "image/png"); err == nil {
		t.Fatal("Store() error = nil for invalid image")
	}
	if _, err := cache.Store(context.Background(), []byte("RIFFxxxxWEBP"), "image/webp"); err == nil {
		t.Fatal("Store() error = nil for malformed WebP")
	}
	if _, err := cache.Store(context.Background(), testWebPHeader(1, 1), "image/webp"); err != nil {
		t.Fatalf("Store() valid WebP header error = %v", err)
	}
	if _, err := cache.Store(context.Background(), testWebPHeader(maxArtworkWidth+1, 1), "image/webp"); err == nil {
		t.Fatal("Store() error = nil for oversized WebP dimensions")
	}
	if _, err := cache.Store(context.Background(), make([]byte, maxArtworkBytes+1), "image/png"); err == nil {
		t.Fatal("Store() error = nil for oversized artwork")
	}
}

func TestCoverStorePersistsValidatedCover(t *testing.T) {
	database := openLibraryTestDB(t)
	cache, err := NewArtworkCache(t.TempDir())
	if err != nil {
		t.Fatalf("NewArtworkCache() error = %v", err)
	}
	store := NewCoverStore(database, cache)
	data := testPNGImage(1, 1)
	cover, err := store.Store(context.Background(), data, "image/png")
	if err != nil {
		t.Fatalf("Store() error = %v", err)
	}
	if cover.ID == "" {
		t.Fatal("cover ID is empty")
	}
	var count int
	if err := database.QueryRow("SELECT COUNT(*) FROM covers WHERE checksum = ?", cover.Checksum).Scan(&count); err != nil {
		t.Fatalf("count cover records: %v", err)
	}
	if count != 1 {
		t.Fatalf("cover record count = %d, want 1", count)
	}
	second, err := store.Store(context.Background(), data, "image/png")
	if err != nil {
		t.Fatalf("duplicate cover Store() error = %v", err)
	}
	if second.ID != cover.ID {
		t.Fatalf("duplicate cover ID = %q, want %q", second.ID, cover.ID)
	}
}

func TestArtworkCacheRejectsOversizedDimensions(t *testing.T) {
	cache, err := NewArtworkCache(t.TempDir())
	if err != nil {
		t.Fatalf("NewArtworkCache() error = %v", err)
	}
	if _, err := cache.Store(context.Background(), testPNGImage(maxArtworkWidth+1, 1), "image/png"); err == nil {
		t.Fatal("Store() error = nil for oversized dimensions")
	}
}

func TestArtworkCacheRejectsSymlinkDirectories(t *testing.T) {
	parent := t.TempDir()
	target := t.TempDir()
	dataLink := filepath.Join(parent, "data-link")
	if err := os.Symlink(target, dataLink); err != nil {
		t.Skipf("symlinks unavailable: %v", err)
	}
	if _, err := NewArtworkCache(dataLink); err == nil {
		t.Fatal("NewArtworkCache(data symlink) error = nil")
	}

	dataDir := t.TempDir()
	if err := os.Symlink(target, filepath.Join(dataDir, "covers")); err != nil {
		t.Fatalf("create covers symlink: %v", err)
	}
	if _, err := NewArtworkCache(dataDir); err == nil {
		t.Fatal("NewArtworkCache(covers symlink) error = nil")
	}

	variantDataDir := t.TempDir()
	coverDir := filepath.Join(variantDataDir, "covers")
	if err := os.Mkdir(coverDir, 0o700); err != nil {
		t.Fatalf("create cover directory: %v", err)
	}
	if err := os.Symlink(target, filepath.Join(coverDir, "variants")); err != nil {
		t.Skipf("variant symlinks unavailable: %v", err)
	}
	if _, err := NewArtworkCache(variantDataDir); err == nil {
		t.Fatal("NewArtworkCache(variants symlink) error = nil")
	}
}

func TestArtworkCacheDetectsCorruptedExistingEntry(t *testing.T) {
	cache, err := NewArtworkCache(t.TempDir())
	if err != nil {
		t.Fatalf("NewArtworkCache() error = %v", err)
	}
	data := testPNGImage(2, 2)
	cover, err := cache.Store(context.Background(), data, "image/png")
	if err != nil {
		t.Fatalf("Store() error = %v", err)
	}
	if err := os.WriteFile(cover.CachePath, make([]byte, len(data)), 0o600); err != nil {
		t.Fatalf("corrupt cache entry: %v", err)
	}
	if _, err := cache.Store(context.Background(), data, "image/png"); err == nil {
		t.Fatal("Store() error = nil for checksum-mismatched cache entry")
	}
}

func testWebPHeader(width, height int) []byte {
	data := make([]byte, 30)
	copy(data[:4], "RIFF")
	binary.LittleEndian.PutUint32(data[4:8], uint32(len(data)-8))
	copy(data[8:12], "WEBP")
	copy(data[12:16], "VP8X")
	binary.LittleEndian.PutUint32(data[16:20], 10)
	width--
	height--
	data[24], data[25], data[26] = byte(width), byte(width>>8), byte(width>>16)
	data[27], data[28], data[29] = byte(height), byte(height>>8), byte(height>>16)
	return data
}

func testPNGImage(width, height int) []byte {
	imageData := image.NewRGBA(image.Rect(0, 0, width, height))
	for y := 0; y < height; y++ {
		for x := 0; x < width; x++ {
			imageData.SetRGBA(x, y, color.RGBA{R: 20, G: 40, B: 60, A: 255})
		}
	}
	var buffer bytes.Buffer
	if err := png.Encode(&buffer, imageData); err != nil {
		panic(err)
	}
	return buffer.Bytes()
}
