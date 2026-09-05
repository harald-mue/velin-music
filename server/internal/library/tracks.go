package library

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"path/filepath"
	"strings"
	"time"
)

const (
	maxMetadataTextRunes = 1024
	maxGenreRunes        = 256
)

// TrackRepository persists parsed track metadata and scan reconciliation state.
type TrackRepository struct {
	db      *sql.DB
	artwork *CoverStore
}

// NewTrackRepository creates a repository backed by db.
func NewTrackRepository(db *sql.DB) *TrackRepository {
	return &TrackRepository{db: db}
}

// NewTrackRepositoryWithArtwork creates a repository that stores embedded
// artwork and associates covers with tracks during upsert.
func NewTrackRepositoryWithArtwork(db *sql.DB, artwork *CoverStore) *TrackRepository {
	return &TrackRepository{db: db, artwork: artwork}
}

// Scan represents one root scan. Tracks are upserted as they are discovered;
// files are removed only when Finish is called successfully.
type Scan struct {
	repository *TrackRepository
	id         string
	rootID     string
	finished   bool
}

// BeginScan starts a reconciliation scan for an existing library root.
func (r *TrackRepository) BeginScan(ctx context.Context, rootID string) (*Scan, error) {
	if r == nil || r.db == nil {
		return nil, errors.New("track repository has no database")
	}
	if strings.TrimSpace(rootID) == "" {
		return nil, errors.New("library root ID must not be empty")
	}
	if err := ctx.Err(); err != nil {
		return nil, err
	}

	var rootExists bool
	if err := r.db.QueryRowContext(ctx, "SELECT EXISTS(SELECT 1 FROM library_roots WHERE id = ?)", rootID).Scan(&rootExists); err != nil {
		return nil, fmt.Errorf("check library root: %w", err)
	}
	if !rootExists {
		return nil, ErrRootNotFound
	}
	var scanRunning bool
	if err := r.db.QueryRowContext(ctx, "SELECT EXISTS(SELECT 1 FROM scan_runs WHERE root_id = ? AND status = 'running')", rootID).Scan(&scanRunning); err != nil {
		return nil, fmt.Errorf("check running library scan: %w", err)
	}
	if scanRunning {
		return nil, ErrScanAlreadyRunning
	}

	id, err := newScanID()
	if err != nil {
		return nil, fmt.Errorf("generate scan ID: %w", err)
	}
	now := time.Now().UTC().Format(time.RFC3339Nano)
	if _, err := r.db.ExecContext(ctx, `
		INSERT INTO scan_runs (id, root_id, status, started_at)
		VALUES (?, ?, 'running', ?)`, id, rootID, now); err != nil {
		return nil, fmt.Errorf("start library scan: %w", err)
	}
	return &Scan{repository: r, id: id, rootID: rootID}, nil
}

// Upsert stores one parsed file and records it as seen for this scan. Both
// operations commit atomically so a seen marker can never outlive its track.
func (s *Scan) Upsert(ctx context.Context, media MediaFile, metadata TrackMetadata) error {
	if err := s.checkUsable(); err != nil {
		return err
	}
	if err := validateTrackInput(media, metadata); err != nil {
		return err
	}
	metadata = normalizeTrackMetadata(metadata)
	if err := ctx.Err(); err != nil {
		return err
	}

	var coverID *string
	if len(metadata.Artwork) > 0 {
		if s.repository.artwork == nil {
			return errors.New("track repository has no artwork store")
		}
		cover, err := s.repository.artwork.Store(ctx, metadata.Artwork, metadata.ArtworkMIME)
		if err != nil {
			return fmt.Errorf("store track artwork: %w", err)
		}
		coverID = &cover.ID
	}

	tx, err := s.repository.db.BeginTx(ctx, nil)
	if err != nil {
		return fmt.Errorf("begin track upsert: %w", err)
	}
	defer func() { _ = tx.Rollback() }()

	artistID, err := upsertArtist(ctx, tx, metadata.Artist)
	if err != nil {
		return err
	}
	albumArtistID, err := upsertArtist(ctx, tx, metadata.AlbumArtist)
	if err != nil {
		return err
	}
	albumID, err := upsertAlbum(ctx, tx, metadata.Album, albumArtistID, metadata.Year, coverID)
	if err != nil {
		return err
	}

	title := metadata.Title
	if title == "" {
		title = normalizeMetadataText(fallbackTitle(media.RelativePath), maxMetadataTextRunes)
	}
	if title == "" {
		title = "Untitled"
	}
	trackID, err := findOrCreateTrackID(ctx, tx, s.rootID, media.RelativePath)
	if err != nil {
		return err
	}
	dateText := nullableYear(metadata.Year)
	now := time.Now().UTC().Format(time.RFC3339Nano)
	if _, err = tx.ExecContext(ctx, `
		INSERT INTO tracks (
			id, root_id, relative_path, format, title, artist_id, album_id,
			album_artist_id, genre, date_text, track_number, total_tracks,
			disc_number, total_discs, duration_ms, sample_rate, bits_per_sample,
			channels, cover_id, file_size, modified_at_ns, created_at, updated_at
		) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
			COALESCE((SELECT created_at FROM tracks WHERE id = ?), ?), ?)
		ON CONFLICT(root_id, relative_path) DO UPDATE SET
			format = excluded.format,
			title = excluded.title,
			artist_id = excluded.artist_id,
			album_id = excluded.album_id,
			album_artist_id = excluded.album_artist_id,
			genre = excluded.genre,
			date_text = excluded.date_text,
			track_number = excluded.track_number,
			total_tracks = excluded.total_tracks,
			disc_number = excluded.disc_number,
			total_discs = excluded.total_discs,
			duration_ms = excluded.duration_ms,
			sample_rate = excluded.sample_rate,
			bits_per_sample = excluded.bits_per_sample,
			channels = excluded.channels,
			cover_id = excluded.cover_id,
			file_size = excluded.file_size,
			modified_at_ns = excluded.modified_at_ns,
			updated_at = excluded.updated_at`,
		trackID, s.rootID, media.RelativePath, media.Format, title, artistID,
		albumID, albumArtistID, nullableString(metadata.Genre), dateText,
		nullableInt(metadata.TrackNumber), nullableInt(metadata.TotalTracks),
		nullableInt(metadata.DiscNumber), nullableInt(metadata.TotalDiscs),
		nullableDuration(metadata.Duration), nullableInt(metadata.SampleRate),
		nullableInt(metadata.BitsPerSample), nullableInt(metadata.Channels),
		coverID, media.Size, modifiedAtNanoseconds(media.ModifiedAt), trackID, now, now,
	); err != nil {
		return fmt.Errorf("upsert track: %w", err)
	}

	if _, err := tx.ExecContext(ctx, "DELETE FROM library_fts WHERE entity_type = 'track' AND entity_id = ?", trackID); err != nil {
		return fmt.Errorf("replace track search entry: %w", err)
	}
	if _, err := tx.ExecContext(ctx, `
		INSERT INTO library_fts (entity_type, entity_id, title, artist, album, genre)
		VALUES (?, ?, ?, ?, ?, ?)`, "track", trackID, title, nullableValue(metadata.Artist),
		nullableValue(metadata.Album), nullableValue(metadata.Genre)); err != nil {
		return fmt.Errorf("index track search entry: %w", err)
	}
	if _, err := tx.ExecContext(ctx, `
		INSERT INTO scan_seen_tracks (scan_run_id, relative_path)
		VALUES (?, ?)
		ON CONFLICT(scan_run_id, relative_path) DO NOTHING`, s.id, media.RelativePath); err != nil {
		return fmt.Errorf("record seen track: %w", err)
	}
	if err := tx.Commit(); err != nil {
		return fmt.Errorf("commit track upsert: %w", err)
	}
	return nil
}

// RetainUnchanged records a track as seen without reparsing it when its source
// format, size, and modification timestamp still match the index.
func (s *Scan) RetainUnchanged(ctx context.Context, media MediaFile) (bool, error) {
	if err := s.checkUsable(); err != nil {
		return false, err
	}
	if err := validateMediaFile(media); err != nil {
		return false, err
	}
	if err := ctx.Err(); err != nil {
		return false, err
	}
	tx, err := s.repository.db.BeginTx(ctx, nil)
	if err != nil {
		return false, fmt.Errorf("begin unchanged-track check: %w", err)
	}
	defer func() { _ = tx.Rollback() }()
	var unchanged bool
	if err := tx.QueryRowContext(ctx, `
		SELECT EXISTS(
			SELECT 1 FROM tracks
			WHERE root_id = ? AND relative_path = ? AND format = ?
			  AND file_size = ? AND modified_at_ns = ?
		)`, s.rootID, media.RelativePath, media.Format, media.Size,
		modifiedAtNanoseconds(media.ModifiedAt)).Scan(&unchanged); err != nil {
		return false, fmt.Errorf("check unchanged track: %w", err)
	}
	if !unchanged {
		return false, nil
	}
	if _, err := tx.ExecContext(ctx, `
		INSERT INTO scan_seen_tracks (scan_run_id, relative_path)
		VALUES (?, ?)
		ON CONFLICT(scan_run_id, relative_path) DO NOTHING`, s.id, media.RelativePath); err != nil {
		return false, fmt.Errorf("record unchanged track: %w", err)
	}
	if err := tx.Commit(); err != nil {
		return false, fmt.Errorf("commit unchanged track: %w", err)
	}
	return true, nil
}

// MarkSeen records a supported relative path without creating a track. This
// is used for files that were found but could not be parsed; they must not be
// mistaken for missing files during reconciliation.
func (s *Scan) MarkSeen(ctx context.Context, relativePath string) error {
	if err := s.checkUsable(); err != nil {
		return err
	}
	if err := validateRelativeTrackPath(relativePath); err != nil {
		return err
	}
	if err := ctx.Err(); err != nil {
		return err
	}
	tx, err := s.repository.db.BeginTx(ctx, nil)
	if err != nil {
		return fmt.Errorf("begin seen marker: %w", err)
	}
	defer func() { _ = tx.Rollback() }()
	if _, err := tx.ExecContext(ctx, `
		INSERT INTO scan_seen_tracks (scan_run_id, relative_path)
		VALUES (?, ?)
		ON CONFLICT(scan_run_id, relative_path) DO NOTHING`, s.id, relativePath); err != nil {
		return fmt.Errorf("record seen marker: %w", err)
	}
	if err := tx.Commit(); err != nil {
		return fmt.Errorf("commit seen marker: %w", err)
	}
	return nil
}

// RecordError stores a safe scan diagnostic without exposing absolute paths.
func (s *Scan) RecordError(ctx context.Context, sourceName, code, message string) error {
	if err := s.checkUsable(); err != nil {
		return err
	}
	if err := ctx.Err(); err != nil {
		return err
	}
	if validateRelativeTrackPath(sourceName) != nil {
		sourceName = ""
	}
	code = truncateRunes(strings.TrimSpace(code), 64)
	if code == "" {
		code = "scan"
	}
	message = truncateRunes(strings.TrimSpace(message), 1024)
	if message == "" {
		message = "scan error"
	}
	now := time.Now().UTC().Format(time.RFC3339Nano)
	if _, err := s.repository.db.ExecContext(ctx, `
		INSERT INTO scan_errors (scan_run_id, root_id, source_name, error_code, message, created_at)
		VALUES (?, ?, ?, ?, ?, ?)`, s.id, s.rootID, nullableString(sourceName), code, message, now); err != nil {
		return fmt.Errorf("record scan error: %w", err)
	}
	return nil
}

// ReportProgress persists bounded live counters for administration clients.
// Callers should throttle updates; the final counts are recomputed exactly by
// Finish from reconciliation state.
func (s *Scan) ReportProgress(ctx context.Context, filesSeen, filesIndexed int) error {
	if err := s.checkUsable(); err != nil {
		return err
	}
	if filesSeen < 0 || filesIndexed < 0 || filesIndexed > filesSeen {
		return errors.New("invalid scan progress")
	}
	if err := ctx.Err(); err != nil {
		return err
	}
	result, err := s.repository.db.ExecContext(ctx, `
		UPDATE scan_runs
		SET files_seen = MAX(files_seen, ?), files_indexed = MAX(files_indexed, ?)
		WHERE id = ? AND status = 'running'`, filesSeen, filesIndexed, s.id)
	if err != nil {
		return fmt.Errorf("report scan progress: %w", err)
	}
	updated, err := result.RowsAffected()
	if err != nil {
		return fmt.Errorf("check reported scan progress: %w", err)
	}
	if updated != 1 {
		return errors.New("library scan is no longer running")
	}
	return nil
}

// Finish commits reconciliation: only files not seen during this complete
// scan are removed. It also records scan counts and cleans temporary markers.
func (s *Scan) Finish(ctx context.Context) error {
	if err := s.checkUsable(); err != nil {
		return err
	}
	if err := ctx.Err(); err != nil {
		return err
	}

	tx, err := s.repository.db.BeginTx(ctx, nil)
	if err != nil {
		return fmt.Errorf("begin scan finish: %w", err)
	}
	defer func() { _ = tx.Rollback() }()

	var seen int
	if err := tx.QueryRowContext(ctx, "SELECT COUNT(*) FROM scan_seen_tracks WHERE scan_run_id = ?", s.id).Scan(&seen); err != nil {
		return fmt.Errorf("count seen tracks: %w", err)
	}
	var scanErrors int
	if err := tx.QueryRowContext(ctx, "SELECT COUNT(*) FROM scan_errors WHERE scan_run_id = ?", s.id).Scan(&scanErrors); err != nil {
		return fmt.Errorf("count scan errors: %w", err)
	}
	indexed := seen - scanErrors
	if indexed < 0 {
		indexed = 0
	}
	result, err := tx.ExecContext(ctx, `
		DELETE FROM tracks
		WHERE root_id = ?
		  AND NOT EXISTS (
			  SELECT 1 FROM scan_seen_tracks
			  WHERE scan_run_id = ? AND relative_path = tracks.relative_path
		  )`, s.rootID, s.id)
	if err != nil {
		return fmt.Errorf("remove missing tracks: %w", err)
	}
	removed, err := result.RowsAffected()
	if err != nil {
		return fmt.Errorf("count removed tracks: %w", err)
	}
	if _, err := tx.ExecContext(ctx, "DELETE FROM library_fts WHERE entity_type = 'track' AND entity_id NOT IN (SELECT id FROM tracks)"); err != nil {
		return fmt.Errorf("remove stale track search entries: %w", err)
	}
	if err := cleanupLibraryIndex(ctx, tx); err != nil {
		return err
	}
	now := time.Now().UTC().Format(time.RFC3339Nano)
	result, err = tx.ExecContext(ctx, `
		UPDATE scan_runs
		SET status = 'completed', finished_at = ?, files_seen = ?, files_indexed = ?, files_removed = ?
		WHERE id = ? AND status = 'running'`, now, seen, indexed, removed, s.id)
	if err != nil {
		return fmt.Errorf("complete library scan: %w", err)
	}
	completed, err := result.RowsAffected()
	if err != nil {
		return fmt.Errorf("check completed library scan: %w", err)
	}
	if completed != 1 {
		return errors.New("library scan is no longer running")
	}
	if _, err := tx.ExecContext(ctx, "DELETE FROM scan_seen_tracks WHERE scan_run_id = ?", s.id); err != nil {
		return fmt.Errorf("clean scan markers: %w", err)
	}
	if err := tx.Commit(); err != nil {
		return fmt.Errorf("commit scan finish: %w", err)
	}
	s.finished = true
	return nil
}

// Fail marks a scan failed or cancelled and deliberately retains all existing tracks.
func (s *Scan) Fail(ctx context.Context, scanErr error) error {
	if err := s.checkUsable(); err != nil {
		return err
	}
	if scanErr == nil {
		scanErr = errors.New("scan failed")
	}
	if err := ctx.Err(); err != nil {
		return err
	}

	tx, err := s.repository.db.BeginTx(ctx, nil)
	if err != nil {
		return fmt.Errorf("begin scan failure: %w", err)
	}
	defer func() { _ = tx.Rollback() }()
	status := "failed"
	if errors.Is(scanErr, context.Canceled) || errors.Is(scanErr, context.DeadlineExceeded) {
		status = "cancelled"
	}
	now := time.Now().UTC().Format(time.RFC3339Nano)
	result, err := tx.ExecContext(ctx, "UPDATE scan_runs SET status = ?, finished_at = ? WHERE id = ? AND status = 'running'", status, now, s.id)
	if err != nil {
		return fmt.Errorf("record failed scan: %w", err)
	}
	failed, err := result.RowsAffected()
	if err != nil {
		return fmt.Errorf("check failed scan: %w", err)
	}
	if failed != 1 {
		return errors.New("library scan is no longer running")
	}
	if _, err := tx.ExecContext(ctx, "DELETE FROM scan_seen_tracks WHERE scan_run_id = ?", s.id); err != nil {
		return fmt.Errorf("clean failed scan markers: %w", err)
	}
	if err := tx.Commit(); err != nil {
		return fmt.Errorf("commit failed scan: %w", err)
	}
	s.finished = true
	return fmt.Errorf("library scan failed: %w", scanErr)
}

func (s *Scan) checkUsable() error {
	if s == nil || s.repository == nil || s.repository.db == nil {
		return errors.New("scan has no repository")
	}
	if s.finished {
		return errors.New("scan is already finished")
	}
	return nil
}

func validateTrackInput(media MediaFile, metadata TrackMetadata) error {
	if err := validateMediaFile(media); err != nil {
		return err
	}
	if metadata.Format != media.Format {
		return fmt.Errorf("metadata format %q does not match media format %q", metadata.Format, media.Format)
	}
	return nil
}

func validateMediaFile(media MediaFile) error {
	if err := validateRelativeTrackPath(media.RelativePath); err != nil {
		return err
	}
	if media.Size < 0 {
		return errors.New("track size must not be negative")
	}
	if media.Format != FormatFLAC && media.Format != FormatMP3 {
		return fmt.Errorf("unsupported track format %q", media.Format)
	}
	return nil
}

func normalizeTrackMetadata(metadata TrackMetadata) TrackMetadata {
	metadata.Title = normalizeMetadataText(metadata.Title, maxMetadataTextRunes)
	metadata.Artist = normalizeMetadataText(metadata.Artist, maxMetadataTextRunes)
	metadata.AlbumArtist = normalizeMetadataText(metadata.AlbumArtist, maxMetadataTextRunes)
	metadata.Album = normalizeMetadataText(metadata.Album, maxMetadataTextRunes)
	metadata.Genre = normalizeMetadataText(metadata.Genre, maxGenreRunes)
	return metadata
}

func normalizeMetadataText(value string, limit int) string {
	return truncateRunes(strings.Join(strings.Fields(value), " "), limit)
}

func validateRelativeTrackPath(relativePath string) error {
	if relativePath == "" || filepath.IsAbs(filepath.FromSlash(relativePath)) {
		return errors.New("track path must be relative")
	}
	clean := filepath.Clean(filepath.FromSlash(relativePath))
	if clean == "." || clean == ".." || strings.HasPrefix(clean, ".."+string(filepath.Separator)) || filepath.ToSlash(clean) != relativePath {
		return errors.New("track path is not a normalized root-relative path")
	}
	return nil
}

func upsertArtist(ctx context.Context, tx *sql.Tx, name string) (*string, error) {
	name = strings.TrimSpace(name)
	if name == "" {
		return nil, nil
	}
	normalized := normalizeName(name)
	id, err := newID()
	if err != nil {
		return nil, fmt.Errorf("generate artist ID: %w", err)
	}
	if _, err := tx.ExecContext(ctx, `
		INSERT INTO artists (id, name, normalized_name) VALUES (?, ?, ?)
		ON CONFLICT(normalized_name) DO NOTHING`, id, name, normalized); err != nil {
		return nil, fmt.Errorf("upsert artist: %w", err)
	}
	var existingID string
	if err := tx.QueryRowContext(ctx, "SELECT id FROM artists WHERE normalized_name = ?", normalized).Scan(&existingID); err != nil {
		return nil, fmt.Errorf("find artist: %w", err)
	}
	return &existingID, nil
}

func upsertAlbum(ctx context.Context, tx *sql.Tx, title string, artistID *string, year int, coverID *string) (*string, error) {
	title = strings.TrimSpace(title)
	if title == "" {
		return nil, nil
	}
	var yearValue any
	if year != 0 {
		yearValue = year
	}
	var id string
	err := tx.QueryRowContext(ctx, `
		SELECT id FROM albums
		WHERE title = ? AND album_artist_id IS ? AND year IS ?
		LIMIT 1`, title, nullableID(artistID), yearValue).Scan(&id)
	if err == nil {
		if coverID != nil {
			if _, err := tx.ExecContext(ctx, "UPDATE albums SET cover_id = ? WHERE id = ?", *coverID, id); err != nil {
				return nil, fmt.Errorf("update album cover: %w", err)
			}
		}
		return &id, nil
	}
	if !errors.Is(err, sql.ErrNoRows) {
		return nil, fmt.Errorf("find album: %w", err)
	}
	id, err = newID()
	if err != nil {
		return nil, fmt.Errorf("generate album ID: %w", err)
	}
	if _, err := tx.ExecContext(ctx, `
		INSERT INTO albums (id, title, album_artist_id, year, cover_id)
		VALUES (?, ?, ?, ?, ?)`, id, title, nullableID(artistID), yearValue, nullableID(coverID)); err != nil {
		return nil, fmt.Errorf("insert album: %w", err)
	}
	return &id, nil
}

// cleanupLibraryIndex refreshes derived album covers and removes metadata rows
// no longer referenced by any indexed track. Cache files are deliberately left
// for separate filesystem garbage collection.
func cleanupLibraryIndex(ctx context.Context, tx *sql.Tx) error {
	if _, err := tx.ExecContext(ctx, `
		UPDATE albums
		SET cover_id = (
			SELECT t.cover_id
			FROM tracks t
			WHERE t.album_id = albums.id AND t.cover_id IS NOT NULL
			ORDER BY COALESCE(t.disc_number, 2147483647),
				COALESCE(t.track_number, 2147483647), t.title COLLATE NOCASE, t.id
			LIMIT 1
		)`); err != nil {
		return fmt.Errorf("refresh album covers: %w", err)
	}
	if _, err := tx.ExecContext(ctx, "DELETE FROM albums WHERE NOT EXISTS (SELECT 1 FROM tracks WHERE tracks.album_id = albums.id)"); err != nil {
		return fmt.Errorf("remove unused albums: %w", err)
	}
	if _, err := tx.ExecContext(ctx, `
		DELETE FROM artists
		WHERE NOT EXISTS (SELECT 1 FROM tracks WHERE tracks.artist_id = artists.id OR tracks.album_artist_id = artists.id)
		  AND NOT EXISTS (SELECT 1 FROM albums WHERE albums.album_artist_id = artists.id)`); err != nil {
		return fmt.Errorf("remove unused artists: %w", err)
	}
	if _, err := tx.ExecContext(ctx, `
		DELETE FROM covers
		WHERE NOT EXISTS (SELECT 1 FROM tracks WHERE tracks.cover_id = covers.id)
		  AND NOT EXISTS (SELECT 1 FROM albums WHERE albums.cover_id = covers.id)`); err != nil {
		return fmt.Errorf("remove unused covers: %w", err)
	}
	return nil
}

func findOrCreateTrackID(ctx context.Context, tx *sql.Tx, rootID, relativePath string) (string, error) {
	var id string
	err := tx.QueryRowContext(ctx, "SELECT id FROM tracks WHERE root_id = ? AND relative_path = ?", rootID, relativePath).Scan(&id)
	if err == nil {
		return id, nil
	}
	if !errors.Is(err, sql.ErrNoRows) {
		return "", fmt.Errorf("find track: %w", err)
	}
	id, err = newID()
	if err != nil {
		return "", fmt.Errorf("generate track ID: %w", err)
	}
	return id, nil
}

func normalizeName(value string) string {
	return strings.Join(strings.Fields(strings.ToLower(value)), " ")
}

func truncateRunes(value string, limit int) string {
	runes := []rune(value)
	if len(runes) <= limit {
		return value
	}
	return string(runes[:limit])
}

func nullableString(value string) any {
	if value == "" {
		return nil
	}
	return value
}

func nullableValue(value string) string {
	return strings.TrimSpace(value)
}

func nullableInt(value int) any {
	if value == 0 {
		return nil
	}
	return value
}

func nullableDuration(value time.Duration) any {
	if value <= 0 {
		return nil
	}
	return value.Milliseconds()
}

func nullableYear(value int) any {
	if value == 0 {
		return nil
	}
	return fmt.Sprintf("%04d", value)
}

func nullableID(value *string) any {
	if value == nil {
		return nil
	}
	return *value
}

func modifiedAtNanoseconds(value time.Time) int64 {
	if value.IsZero() {
		return 0
	}
	return value.UnixNano()
}

func newScanID() (string, error) {
	return newID()
}
