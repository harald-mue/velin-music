package library

import (
	"bytes"
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"fmt"
	"image"
	_ "image/gif"
	_ "image/jpeg"
	_ "image/png"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"
)

const (
	maxArtworkBytes  = 8 << 20
	maxArtworkWidth  = 10000
	maxArtworkHeight = 10000
	maxArtworkPixels = 50_000_000
)

// Cover is an internally stored artwork record. CachePath must never be
// returned through the public API.
type Cover struct {
	ID        string
	MIMEType  string
	CachePath string
	ByteSize  int64
	Checksum  string
}

// ArtworkCache stores content-addressed artwork below a managed data directory.
type ArtworkCache struct {
	directory string
}

// NewArtworkCache creates an artwork cache below dataDir.
func NewArtworkCache(dataDir string) (*ArtworkCache, error) {
	if strings.TrimSpace(dataDir) == "" {
		return nil, errors.New("artwork data directory must not be empty")
	}
	parentInfo, err := os.Lstat(dataDir)
	if err != nil {
		return nil, fmt.Errorf("inspect artwork data directory: %w", err)
	}
	if parentInfo.Mode()&os.ModeSymlink != 0 || !parentInfo.IsDir() {
		return nil, errors.New("artwork data directory must be a non-symlink directory")
	}
	directory := filepath.Join(dataDir, "covers")
	if err := os.MkdirAll(directory, 0o700); err != nil {
		return nil, fmt.Errorf("create artwork cache: %w", err)
	}
	info, err := os.Lstat(directory)
	if err != nil {
		return nil, fmt.Errorf("inspect artwork cache: %w", err)
	}
	if info.Mode()&os.ModeSymlink != 0 || !info.IsDir() || info.Mode().Perm()&0o077 != 0 {
		return nil, errors.New("artwork cache directory is invalid or accessible by other users")
	}
	return &ArtworkCache{directory: directory}, nil
}

// Store validates and atomically stores artwork. Identical artwork reuses its
// content-addressed file. The source audio file is never modified.
func (c *ArtworkCache) Store(ctx context.Context, data []byte, declaredMIME string) (Cover, error) {
	if c == nil || c.directory == "" {
		return Cover{}, errors.New("artwork cache is not configured")
	}
	if err := ctx.Err(); err != nil {
		return Cover{}, err
	}
	mimeType, extension, err := validateArtwork(data, declaredMIME)
	if err != nil {
		return Cover{}, err
	}
	checksum := sha256.Sum256(data)
	checksumText := hex.EncodeToString(checksum[:])
	path := filepath.Join(c.directory, checksumText+extension)
	if info, err := os.Lstat(path); err == nil {
		if info.Mode()&os.ModeSymlink != 0 || !info.Mode().IsRegular() || info.Size() != int64(len(data)) {
			return Cover{}, errors.New("artwork cache contains an invalid existing entry")
		}
		if err := verifyArtworkChecksum(path, checksum); err != nil {
			return Cover{}, err
		}
		return Cover{MIMEType: mimeType, CachePath: path, ByteSize: info.Size(), Checksum: checksumText}, nil
	} else if !errors.Is(err, os.ErrNotExist) {
		return Cover{}, fmt.Errorf("inspect artwork cache entry: %w", err)
	}

	temporary, err := os.CreateTemp(c.directory, ".cover-*")
	if err != nil {
		return Cover{}, fmt.Errorf("create artwork cache entry: %w", err)
	}
	temporaryPath := temporary.Name()
	removeTemporary := true
	defer func() {
		_ = temporary.Close()
		if removeTemporary {
			_ = os.Remove(temporaryPath)
		}
	}()
	if err := temporary.Chmod(0o600); err != nil {
		return Cover{}, fmt.Errorf("set artwork cache permissions: %w", err)
	}
	if _, err := temporary.Write(data); err != nil {
		return Cover{}, fmt.Errorf("write artwork cache entry: %w", err)
	}
	if err := temporary.Sync(); err != nil {
		return Cover{}, fmt.Errorf("sync artwork cache entry: %w", err)
	}
	if err := temporary.Close(); err != nil {
		return Cover{}, fmt.Errorf("close artwork cache entry: %w", err)
	}
	if err := os.Rename(temporaryPath, path); err != nil {
		if !errors.Is(err, os.ErrExist) {
			return Cover{}, fmt.Errorf("install artwork cache entry: %w", err)
		}
	} else {
		removeTemporary = false
	}
	if _, err := os.Stat(path); err != nil {
		return Cover{}, fmt.Errorf("verify artwork cache entry: %w", err)
	}
	return Cover{MIMEType: mimeType, CachePath: path, ByteSize: int64(len(data)), Checksum: checksumText}, nil
}

// CoverStore links validated cache files to SQLite cover records.
type CoverStore struct {
	db    *sql.DB
	cache *ArtworkCache
}

// NewCoverStore creates a database-backed artwork store.
func NewCoverStore(db *sql.DB, cache *ArtworkCache) *CoverStore {
	return &CoverStore{db: db, cache: cache}
}

// Store validates/cache-stores artwork and returns its stable database record.
func (s *CoverStore) Store(ctx context.Context, data []byte, declaredMIME string) (*Cover, error) {
	if s == nil || s.db == nil || s.cache == nil {
		return nil, errors.New("cover store is not configured")
	}
	cover, err := s.cache.Store(ctx, data, declaredMIME)
	if err != nil {
		return nil, err
	}
	id := cover.Checksum
	now := time.Now().UTC().Format(time.RFC3339Nano)
	if _, err := s.db.ExecContext(ctx, `
		INSERT INTO covers (id, mime_type, cache_path, byte_size, checksum, created_at)
		VALUES (?, ?, ?, ?, ?, ?)
		ON CONFLICT(checksum) DO NOTHING`, id, cover.MIMEType, cover.CachePath, cover.ByteSize, cover.Checksum, now); err != nil {
		return nil, fmt.Errorf("store cover record: %w", err)
	}
	if err := s.db.QueryRowContext(ctx, "SELECT id, mime_type, cache_path, byte_size, checksum FROM covers WHERE checksum = ?", cover.Checksum).Scan(&cover.ID, &cover.MIMEType, &cover.CachePath, &cover.ByteSize, &cover.Checksum); err != nil {
		return nil, fmt.Errorf("read cover record: %w", err)
	}
	return &cover, nil
}

// CoverReader serves indexed artwork from the managed cache.
type CoverReader struct {
	db    *sql.DB
	cache *ArtworkCache
}

// CoverFile contains safe response metadata for a cached cover.
type CoverFile struct {
	MIMEType string
	ModTime  time.Time
}

// NewCoverReader creates a reader backed by db and cache.
func NewCoverReader(db *sql.DB, cache *ArtworkCache) *CoverReader {
	return &CoverReader{db: db, cache: cache}
}

// Open returns a readable cache file for an indexed cover ID.
func (r *CoverReader) Open(ctx context.Context, id string) (*os.File, CoverFile, error) {
	var content CoverFile
	if r == nil || r.db == nil || r.cache == nil {
		return nil, content, errors.New("cover reader is not configured")
	}
	if err := ctx.Err(); err != nil {
		return nil, content, err
	}
	id = strings.TrimSpace(id)
	if id == "" {
		return nil, content, errors.New("cover ID must not be empty")
	}
	if len(id) > 128 {
		return nil, content, errors.New("cover ID is too long")
	}

	var cachePath, mimeType, createdAt string
	var byteSize int64
	err := r.db.QueryRowContext(ctx, `
		SELECT mime_type, cache_path, byte_size, created_at
		FROM covers
		WHERE id = ?`, id).Scan(&mimeType, &cachePath, &byteSize, &createdAt)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, content, ErrNotFound
	}
	if err != nil {
		return nil, content, fmt.Errorf("load cover: %w", err)
	}
	mimeType, ok := safeCoverMIME(mimeType)
	if !ok {
		return nil, content, errors.New("cover MIME type is not supported")
	}
	modTime, err := time.Parse(time.RFC3339Nano, createdAt)
	if err != nil {
		return nil, content, fmt.Errorf("parse cover created_at: %w", err)
	}
	file, err := r.cache.openCached(cachePath, byteSize)
	if err != nil {
		return nil, content, err
	}
	return file, CoverFile{MIMEType: mimeType, ModTime: modTime}, nil
}

func (c *ArtworkCache) openCached(path string, expectedSize int64) (*os.File, error) {
	if c == nil || c.directory == "" {
		return nil, errors.New("artwork cache is not configured")
	}
	cleanPath := filepath.Clean(path)
	cacheDir := filepath.Clean(c.directory)
	relative, err := filepath.Rel(cacheDir, cleanPath)
	if err != nil || relative == ".." || strings.HasPrefix(relative, ".."+string(os.PathSeparator)) {
		return nil, errors.New("cover cache path is outside managed cache")
	}
	info, err := os.Lstat(cleanPath)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return nil, ErrNotFound
		}
		return nil, fmt.Errorf("inspect cover cache file: %w", err)
	}
	if info.Mode()&os.ModeSymlink != 0 || !info.Mode().IsRegular() {
		return nil, errors.New("cover cache file is invalid")
	}
	if info.Size() != expectedSize {
		return nil, errors.New("cover cache file size mismatch")
	}
	file, err := os.Open(cleanPath)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return nil, ErrNotFound
		}
		return nil, fmt.Errorf("open cover cache file: %w", err)
	}
	return file, nil
}

func safeCoverMIME(value string) (string, bool) {
	switch normalizeMIME(value) {
	case "image/jpeg", "image/png", "image/gif", "image/webp":
		return normalizeMIME(value), true
	default:
		return "", false
	}
}

func verifyArtworkChecksum(path string, expected [sha256.Size]byte) error {
	file, err := os.Open(path)
	if err != nil {
		return fmt.Errorf("open existing artwork cache entry: %w", err)
	}
	defer file.Close()
	hash := sha256.New()
	if _, err := io.Copy(hash, io.LimitReader(file, maxArtworkBytes+1)); err != nil {
		return fmt.Errorf("read existing artwork cache entry: %w", err)
	}
	if !bytes.Equal(hash.Sum(nil), expected[:]) {
		return errors.New("artwork cache checksum mismatch")
	}
	return nil
}

func validateArtwork(data []byte, declaredMIME string) (string, string, error) {
	if len(data) == 0 {
		return "", "", errors.New("artwork is empty")
	}
	if len(data) > maxArtworkBytes {
		return "", "", fmt.Errorf("artwork exceeds %d-byte limit", maxArtworkBytes)
	}
	mimeType, extension, ok := sniffArtwork(data)
	if !ok {
		return "", "", errors.New("artwork format is not supported")
	}
	declaredMIME = normalizeMIME(declaredMIME)
	if declaredMIME != "" && declaredMIME != mimeType {
		return "", "", fmt.Errorf("artwork MIME type %q does not match detected type %q", declaredMIME, mimeType)
	}
	var width, height int
	var err error
	if mimeType == "image/webp" {
		width, height, err = webPDimensions(data)
	} else {
		var config image.Config
		config, _, err = image.DecodeConfig(bytes.NewReader(data))
		width, height = config.Width, config.Height
	}
	if err != nil {
		return "", "", fmt.Errorf("decode artwork header: %w", err)
	}
	if width <= 0 || height <= 0 || width > maxArtworkWidth || height > maxArtworkHeight || int64(width)*int64(height) > maxArtworkPixels {
		return "", "", errors.New("artwork dimensions exceed limits")
	}
	return mimeType, extension, nil
}

func webPDimensions(data []byte) (int, int, error) {
	if len(data) < 20 || string(data[:4]) != "RIFF" || string(data[8:12]) != "WEBP" {
		return 0, 0, errors.New("invalid WebP container")
	}
	declaredSize := uint64(binary.LittleEndian.Uint32(data[4:8])) + 8
	if declaredSize > uint64(len(data)) || declaredSize < 20 {
		return 0, 0, errors.New("invalid WebP container size")
	}
	for offset := 12; offset+8 <= int(declaredSize); {
		chunkType := string(data[offset : offset+4])
		chunkSize := int(binary.LittleEndian.Uint32(data[offset+4 : offset+8]))
		start := offset + 8
		end := start + chunkSize
		if chunkSize < 0 || end < start || end > int(declaredSize) || end > len(data) {
			return 0, 0, errors.New("invalid WebP chunk size")
		}
		chunk := data[start:end]
		switch chunkType {
		case "VP8X":
			if len(chunk) < 10 {
				return 0, 0, errors.New("invalid WebP VP8X header")
			}
			width := 1 + int(chunk[4]) + int(chunk[5])<<8 + int(chunk[6])<<16
			height := 1 + int(chunk[7]) + int(chunk[8])<<8 + int(chunk[9])<<16
			return width, height, nil
		case "VP8L":
			if len(chunk) < 5 || chunk[0] != 0x2f {
				return 0, 0, errors.New("invalid WebP VP8L header")
			}
			bits := binary.LittleEndian.Uint32(chunk[1:5])
			return int(bits&0x3fff) + 1, int((bits>>14)&0x3fff) + 1, nil
		case "VP8 ":
			if len(chunk) < 10 || chunk[3] != 0x9d || chunk[4] != 0x01 || chunk[5] != 0x2a {
				return 0, 0, errors.New("invalid WebP VP8 header")
			}
			width := int(binary.LittleEndian.Uint16(chunk[6:8]) & 0x3fff)
			height := int(binary.LittleEndian.Uint16(chunk[8:10]) & 0x3fff)
			return width, height, nil
		}
		offset = end + chunkSize%2
	}
	return 0, 0, errors.New("WebP image chunk not found")
}

func sniffArtwork(data []byte) (string, string, bool) {
	detected := http.DetectContentType(data)
	switch detected {
	case "image/jpeg":
		return "image/jpeg", ".jpg", true
	case "image/png":
		return "image/png", ".png", true
	case "image/gif":
		return "image/gif", ".gif", true
	}
	if len(data) >= 12 && string(data[:4]) == "RIFF" && string(data[8:12]) == "WEBP" {
		return "image/webp", ".webp", true
	}
	return "", "", false
}

func normalizeMIME(value string) string {
	value = strings.ToLower(strings.TrimSpace(value))
	switch value {
	case "image/jpg", "image/jpeg", "jpg", "jpeg":
		return "image/jpeg"
	case "image/png", "png":
		return "image/png"
	case "image/gif", "gif":
		return "image/gif"
	case "image/webp", "webp":
		return "image/webp"
	default:
		return value
	}
}
