package library

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"os"
	"strings"
	"time"
)

// TrackStream contains safe response metadata for an original audio file.
type TrackStream struct {
	MIMEType string
	ModTime  time.Time
}

// TrackStreamer resolves indexed tracks to readable original audio files.
type TrackStreamer struct {
	db *sql.DB
}

// NewTrackStreamer creates a streamer backed by db.
func NewTrackStreamer(db *sql.DB) *TrackStreamer {
	return &TrackStreamer{db: db}
}

// Open returns a readable original audio file for an indexed track ID.
func (s *TrackStreamer) Open(ctx context.Context, trackID string) (*os.File, TrackStream, error) {
	var stream TrackStream
	if s == nil || s.db == nil {
		return nil, stream, errors.New("track streamer is not configured")
	}
	if err := ctx.Err(); err != nil {
		return nil, stream, err
	}
	trackID = strings.TrimSpace(trackID)
	if trackID == "" {
		return nil, stream, errors.New("library item ID must not be empty")
	}
	if len(trackID) > maxOpaqueIDLength {
		return nil, stream, errors.New("library item ID is too long")
	}

	var (
		format       string
		relativePath string
		fileSize     int64
		modifiedAtNS int64
		rootPath     string
	)
	err := s.db.QueryRowContext(ctx, `
		SELECT t.format, t.relative_path, t.file_size, t.modified_at_ns, r.path
		FROM tracks t
		JOIN library_roots r ON r.id = t.root_id
		WHERE t.id = ?`, trackID).Scan(&format, &relativePath, &fileSize, &modifiedAtNS, &rootPath)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, stream, ErrNotFound
	}
	if err != nil {
		return nil, stream, fmt.Errorf("load track stream: %w", err)
	}

	mimeType, ok := streamMIME(Format(format))
	if !ok {
		return nil, stream, errors.New("track format is not supported")
	}

	modifiedAt := time.Unix(0, modifiedAtNS).UTC()
	media := MediaFile{
		RelativePath: relativePath,
		Format:       Format(format),
		Size:         fileSize,
		ModifiedAt:   modifiedAt,
	}
	file, modTime, err := openVerifiedMediaFile(Root{Path: rootPath}, media)
	if err != nil {
		return nil, stream, err
	}
	return file, TrackStream{MIMEType: mimeType, ModTime: modTime}, nil
}

func openVerifiedMediaFile(root Root, media MediaFile) (*os.File, time.Time, error) {
	path, err := safeMediaPath(root, media.RelativePath)
	if err != nil {
		return nil, time.Time{}, err
	}

	file, err := os.Open(path)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return nil, time.Time{}, ErrNotFound
		}
		return nil, time.Time{}, fmt.Errorf("open media file: %w", err)
	}

	info, err := file.Stat()
	if err != nil {
		_ = file.Close()
		return nil, time.Time{}, fmt.Errorf("stat media file: %w", err)
	}
	pathInfo, err := os.Lstat(path)
	if err != nil {
		_ = file.Close()
		return nil, time.Time{}, fmt.Errorf("recheck media file: %w", err)
	}
	if pathInfo.Mode()&os.ModeSymlink != 0 || !info.Mode().IsRegular() || !pathInfo.Mode().IsRegular() || !os.SameFile(info, pathInfo) {
		_ = file.Close()
		return nil, time.Time{}, errors.New("media file changed or is not a regular file")
	}
	if media.Size > 0 && info.Size() != media.Size {
		_ = file.Close()
		return nil, time.Time{}, errors.New("media file changed since indexing")
	}
	if !media.ModifiedAt.IsZero() && !info.ModTime().Equal(media.ModifiedAt) {
		_ = file.Close()
		return nil, time.Time{}, errors.New("media file changed since indexing")
	}
	return file, info.ModTime(), nil
}

func streamMIME(format Format) (string, bool) {
	switch format {
	case FormatFLAC:
		return "audio/flac", true
	case FormatMP3:
		return "audio/mpeg", true
	default:
		return "", false
	}
}
