package library

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"strings"
	"unicode"
	"unicode/utf8"
)

const (
	defaultPageLimit    = 50
	maxPageLimit        = 200
	maxCursorLength     = 16384
	maxOpaqueIDLength   = 128
	maxSearchQueryBytes = 2048
	maxSearchTerms      = 16
	maxSearchTermRunes  = 64
)

// PageOptions controls keyset pagination. Cursors are opaque to API clients.
type PageOptions struct {
	Limit  int
	Cursor string
}

// Page is a bounded, deterministically ordered result page.
type Page[T any] struct {
	Items      []T    `json:"items"`
	NextCursor string `json:"next_cursor,omitempty"`
	HasMore    bool   `json:"has_more"`
}

// Artist is the public library representation of an artist. It contains no
// filesystem identity.
type Artist struct {
	ID         string `json:"id"`
	Name       string `json:"name"`
	AlbumCount int    `json:"album_count"`
	TrackCount int    `json:"track_count"`
}

// Album is the public library representation of an album.
type Album struct {
	ID         string  `json:"id"`
	Title      string  `json:"title"`
	ArtistID   *string `json:"artist_id,omitempty"`
	ArtistName *string `json:"artist_name,omitempty"`
	Year       *int    `json:"year,omitempty"`
	CoverID    *string `json:"cover_id,omitempty"`
	TrackCount int     `json:"track_count"`
	AddedAt    string  `json:"added_at"`
}

// Track is the public library representation of a track. Relative paths,
// roots, and source file identity are intentionally excluded.
type Track struct {
	ID              string  `json:"id"`
	Title           string  `json:"title"`
	Format          string  `json:"format"`
	ArtistID        *string `json:"artist_id,omitempty"`
	ArtistName      *string `json:"artist_name,omitempty"`
	AlbumID         *string `json:"album_id,omitempty"`
	AlbumTitle      *string `json:"album_title,omitempty"`
	AlbumArtistID   *string `json:"album_artist_id,omitempty"`
	AlbumArtistName *string `json:"album_artist_name,omitempty"`
	Genre           *string `json:"genre,omitempty"`
	DateText        *string `json:"date,omitempty"`
	TrackNumber     *int    `json:"track_number,omitempty"`
	TotalTracks     *int    `json:"total_tracks,omitempty"`
	DiscNumber      *int    `json:"disc_number,omitempty"`
	TotalDiscs      *int    `json:"total_discs,omitempty"`
	DurationMS      *int64  `json:"duration_ms,omitempty"`
	SampleRate      *int    `json:"sample_rate,omitempty"`
	BitsPerSample   *int    `json:"bits_per_sample,omitempty"`
	Channels        *int    `json:"channels,omitempty"`
	CoverID         *string `json:"cover_id,omitempty"`
}

// QueryRepository reads the indexed library without exposing source paths.
type QueryRepository struct {
	db *sql.DB
}

// NewQueryRepository creates a read-only library query repository backed by db.
func NewQueryRepository(db *sql.DB) *QueryRepository {
	return &QueryRepository{db: db}
}

// ListArtists returns artists with at least one indexed track, ordered by
// normalized name and opaque ID.
func (r *QueryRepository) ListArtists(ctx context.Context, options PageOptions) (Page[Artist], error) {
	var page Page[Artist]
	limit, cursor, err := preparePage("artists", "", options)
	if err != nil {
		return page, err
	}
	if err := r.check(ctx); err != nil {
		return page, err
	}

	query := `
		SELECT a.id, a.name,
			(SELECT COUNT(*) FROM albums al
			 WHERE al.album_artist_id = a.id
			   AND EXISTS (SELECT 1 FROM tracks at WHERE at.album_id = al.id)),
			(SELECT COUNT(DISTINCT t.id) FROM tracks t
			 WHERE t.artist_id = a.id OR t.album_artist_id = a.id)
		FROM artists a
		WHERE EXISTS (SELECT 1 FROM tracks t WHERE t.artist_id = a.id OR t.album_artist_id = a.id)`
	args := make([]any, 0, 3)
	if cursor != nil {
		query += ` AND (a.normalized_name > ? OR (a.normalized_name = ? AND a.id > ?))`
		args = append(args, cursor.Key, cursor.Key, cursor.ID)
	}
	query += ` ORDER BY a.normalized_name ASC, a.id ASC LIMIT ?`
	args = append(args, limit+1)

	rows, err := r.db.QueryContext(ctx, query, args...)
	if err != nil {
		return page, fmt.Errorf("list artists: %w", err)
	}
	defer rows.Close()
	for rows.Next() {
		var artist Artist
		if err := rows.Scan(&artist.ID, &artist.Name, &artist.AlbumCount, &artist.TrackCount); err != nil {
			return page, fmt.Errorf("scan artist: %w", err)
		}
		page.Items = append(page.Items, artist)
	}
	if err := rows.Err(); err != nil {
		return page, fmt.Errorf("read artists: %w", err)
	}
	return finishPage(&page, limit, func(item Artist) pageCursor {
		return pageCursor{Kind: "artists", Key: normalizeName(item.Name), ID: item.ID}
	}), nil
}

// GetArtist returns an artist by opaque ID.
func (r *QueryRepository) GetArtist(ctx context.Context, id string) (Artist, error) {
	var artist Artist
	if err := r.checkID(ctx, id); err != nil {
		return artist, err
	}
	err := r.db.QueryRowContext(ctx, `
		SELECT a.id, a.name,
			(SELECT COUNT(*) FROM albums al
			 WHERE al.album_artist_id = a.id
			   AND EXISTS (SELECT 1 FROM tracks at WHERE at.album_id = al.id)),
			(SELECT COUNT(DISTINCT t.id) FROM tracks t
			 WHERE t.artist_id = a.id OR t.album_artist_id = a.id)
		FROM artists a
		WHERE a.id = ?
		  AND EXISTS (SELECT 1 FROM tracks t WHERE t.artist_id = a.id OR t.album_artist_id = a.id)`, id).Scan(&artist.ID, &artist.Name, &artist.AlbumCount, &artist.TrackCount)
	if errors.Is(err, sql.ErrNoRows) {
		return artist, ErrNotFound
	}
	if err != nil {
		return artist, fmt.Errorf("get artist: %w", err)
	}
	return artist, nil
}

// ListAlbums returns albums that have at least one indexed track, ordered by
// title and opaque ID.
func (r *QueryRepository) ListAlbums(ctx context.Context, options PageOptions) (Page[Album], error) {
	var page Page[Album]
	limit, cursor, err := preparePage("albums", "", options)
	if err != nil {
		return page, err
	}
	if err := r.check(ctx); err != nil {
		return page, err
	}

	query := `
		SELECT al.id, al.title, al.album_artist_id, aa.name, al.year, al.cover_id,
			COUNT(t.id), MAX(t.created_at)
		FROM albums al
		LEFT JOIN artists aa ON aa.id = al.album_artist_id
		JOIN tracks t ON t.album_id = al.id
		WHERE 1 = 1`
	args := make([]any, 0, 3)
	if cursor != nil {
		query += ` AND (al.title COLLATE NOCASE > ? OR (al.title COLLATE NOCASE = ? AND al.id > ?))`
		args = append(args, cursor.Key, cursor.Key, cursor.ID)
	}
	query += ` GROUP BY al.id, al.title, al.album_artist_id, aa.name, al.year, al.cover_id
		ORDER BY al.title COLLATE NOCASE ASC, al.id ASC LIMIT ?`
	args = append(args, limit+1)

	rows, err := r.db.QueryContext(ctx, query, args...)
	if err != nil {
		return page, fmt.Errorf("list albums: %w", err)
	}
	defer rows.Close()
	for rows.Next() {
		album, err := scanAlbum(rows)
		if err != nil {
			return page, fmt.Errorf("scan album: %w", err)
		}
		page.Items = append(page.Items, album)
	}
	if err := rows.Err(); err != nil {
		return page, fmt.Errorf("read albums: %w", err)
	}
	return finishPage(&page, limit, func(item Album) pageCursor {
		return pageCursor{Kind: "albums", Key: item.Title, ID: item.ID}
	}), nil
}

// GetAlbum returns an album by opaque ID.
func (r *QueryRepository) GetAlbum(ctx context.Context, id string) (Album, error) {
	var album Album
	if err := r.checkID(ctx, id); err != nil {
		return album, err
	}
	row := r.db.QueryRowContext(ctx, `
		SELECT al.id, al.title, al.album_artist_id, aa.name, al.year, al.cover_id,
			COUNT(t.id), MAX(t.created_at)
		FROM albums al
		LEFT JOIN artists aa ON aa.id = al.album_artist_id
		JOIN tracks t ON t.album_id = al.id
		WHERE al.id = ?
		GROUP BY al.id, al.title, al.album_artist_id, aa.name, al.year, al.cover_id`, id)
	var err error
	album, err = scanAlbum(row)
	if errors.Is(err, sql.ErrNoRows) {
		return album, ErrNotFound
	}
	if err != nil {
		return album, fmt.Errorf("get album: %w", err)
	}
	return album, nil
}

// TrackListOptions controls track pagination and optional relationship filters.
type TrackListOptions struct {
	PageOptions
	ArtistID string
	AlbumID  string
}

// ListTracks returns tracks ordered by title and opaque ID. Optional filters
// restrict results to an artist or album.
func (r *QueryRepository) ListTracks(ctx context.Context, options TrackListOptions) (Page[Track], error) {
	var page Page[Track]
	artistID := strings.TrimSpace(options.ArtistID)
	albumID := strings.TrimSpace(options.AlbumID)
	if len(artistID) > maxOpaqueIDLength || len(albumID) > maxOpaqueIDLength {
		return page, errors.New("library filter ID is too long")
	}
	scope := artistID + "\x00" + albumID
	cursorKind := "tracks"
	if albumID != "" {
		cursorKind = "album_tracks"
	}
	limit, cursor, err := preparePage(cursorKind, scope, options.PageOptions)
	if err != nil {
		return page, err
	}
	if err := r.check(ctx); err != nil {
		return page, err
	}

	query := trackSelect + ` WHERE 1 = 1`
	args := make([]any, 0, 6)
	if artistID != "" {
		query += ` AND (t.artist_id = ? OR t.album_artist_id = ?)`
		args = append(args, artistID, artistID)
	}
	if albumID != "" {
		query += ` AND t.album_id = ?`
		args = append(args, albumID)
	}
	if cursor != nil {
		if albumID != "" {
			if cursor.DiscNumber == nil || cursor.TrackNumber == nil {
				return page, errors.New("invalid page cursor")
			}
			query += ` AND (
				COALESCE(t.disc_number, 2147483647),
				COALESCE(t.track_number, 2147483647),
				t.title COLLATE NOCASE,
				t.id
			) > (?, ?, ?, ?)`
			args = append(args, *cursor.DiscNumber, *cursor.TrackNumber, cursor.Key, cursor.ID)
		} else {
			query += ` AND (t.title COLLATE NOCASE > ? OR (t.title COLLATE NOCASE = ? AND t.id > ?))`
			args = append(args, cursor.Key, cursor.Key, cursor.ID)
		}
	}
	if albumID != "" {
		query += ` ORDER BY
			COALESCE(t.disc_number, 2147483647) ASC,
			COALESCE(t.track_number, 2147483647) ASC,
			t.title COLLATE NOCASE ASC,
			t.id ASC LIMIT ?`
	} else {
		query += ` ORDER BY t.title COLLATE NOCASE ASC, t.id ASC LIMIT ?`
	}
	args = append(args, limit+1)

	rows, err := r.db.QueryContext(ctx, query, args...)
	if err != nil {
		return page, fmt.Errorf("list tracks: %w", err)
	}
	defer rows.Close()
	for rows.Next() {
		track, err := scanTrack(rows)
		if err != nil {
			return page, fmt.Errorf("scan track: %w", err)
		}
		page.Items = append(page.Items, track)
	}
	if err := rows.Err(); err != nil {
		return page, fmt.Errorf("read tracks: %w", err)
	}
	return finishPage(&page, limit, func(item Track) pageCursor {
		result := pageCursor{Kind: cursorKind, Scope: scope, Key: item.Title, ID: item.ID}
		if albumID != "" {
			discNumber := sortableTrackNumber(item.DiscNumber)
			trackNumber := sortableTrackNumber(item.TrackNumber)
			result.DiscNumber = &discNumber
			result.TrackNumber = &trackNumber
		}
		return result
	}), nil
}

// SearchTracks returns ranked track matches. User input is compiled into
// literal Unicode token prefixes rather than accepted as raw FTS5 syntax.
func (r *QueryRepository) SearchTracks(ctx context.Context, searchQuery string, options PageOptions) (Page[Track], error) {
	var page Page[Track]
	if err := r.check(ctx); err != nil {
		return page, err
	}
	matchQuery, scope, err := compileSearchQuery(searchQuery)
	if err != nil {
		return page, err
	}
	limit, cursor, err := preparePage("search_tracks", scope, options)
	if err != nil {
		return page, err
	}
	if cursor != nil && (cursor.Rank == nil || math.IsNaN(*cursor.Rank) || math.IsInf(*cursor.Rank, 0)) {
		return page, errors.New("invalid page cursor")
	}

	query := `
		WITH matches AS MATERIALIZED (
			SELECT entity_id,
				bm25(library_fts, 0.0, 0.0, 10.0, 5.0, 3.0, 1.0) AS rank
			FROM library_fts
			WHERE library_fts MATCH ? AND entity_type = 'track'
		)
		SELECT ` + trackColumns + `, matches.rank
		` + trackJoins + `
		JOIN matches ON matches.entity_id = t.id
		WHERE 1 = 1`
	args := []any{matchQuery}
	if cursor != nil {
		query += ` AND (
			matches.rank > ? OR (
				matches.rank = ? AND (
					t.title COLLATE NOCASE > ? OR
					(t.title COLLATE NOCASE = ? AND t.id > ?)
				)
			)
		)`
		args = append(args, *cursor.Rank, *cursor.Rank, cursor.Key, cursor.Key, cursor.ID)
	}
	query += ` ORDER BY matches.rank ASC, t.title COLLATE NOCASE ASC, t.id ASC LIMIT ?`
	args = append(args, limit+1)

	rows, err := r.db.QueryContext(ctx, query, args...)
	if err != nil {
		return page, fmt.Errorf("search tracks: %w", err)
	}
	defer rows.Close()
	ranks := make([]float64, 0, limit+1)
	for rows.Next() {
		var rank float64
		track, err := scanTrack(rows, &rank)
		if err != nil {
			return page, fmt.Errorf("scan track search result: %w", err)
		}
		page.Items = append(page.Items, track)
		ranks = append(ranks, rank)
	}
	if err := rows.Err(); err != nil {
		return page, fmt.Errorf("read track search results: %w", err)
	}
	if page.Items == nil {
		page.Items = make([]Track, 0)
	}
	if len(page.Items) <= limit {
		return page, nil
	}
	page.HasMore = true
	page.Items = page.Items[:limit]
	rank := ranks[limit-1]
	last := page.Items[limit-1]
	page.NextCursor = encodeCursor(pageCursor{
		Kind: "search_tracks", Scope: scope, Key: last.Title, ID: last.ID, Rank: &rank,
	})
	return page, nil
}

// GetTrack returns a track by opaque ID.
func (r *QueryRepository) GetTrack(ctx context.Context, id string) (Track, error) {
	var track Track
	if err := r.checkID(ctx, id); err != nil {
		return track, err
	}
	track, err := scanTrack(r.db.QueryRowContext(ctx, trackSelect+` WHERE t.id = ?`, id))
	if errors.Is(err, sql.ErrNoRows) {
		return track, ErrNotFound
	}
	if err != nil {
		return track, fmt.Errorf("get track: %w", err)
	}
	return track, nil
}

const trackColumns = `t.id, t.title, t.format,
		t.artist_id, a.name, t.album_id, al.title,
		t.album_artist_id, aa.name, t.genre, t.date_text,
		t.track_number, t.total_tracks, t.disc_number, t.total_discs,
		t.duration_ms, t.sample_rate, t.bits_per_sample, t.channels, t.cover_id`

const trackJoins = `FROM tracks t
	LEFT JOIN artists a ON a.id = t.artist_id
	LEFT JOIN albums al ON al.id = t.album_id
	LEFT JOIN artists aa ON aa.id = t.album_artist_id`

const trackSelect = `SELECT ` + trackColumns + ` ` + trackJoins

var (
	// ErrNotFound indicates that an opaque library ID has no visible item.
	ErrNotFound = errors.New("library item not found")
	// ErrInvalidSearchQuery indicates an empty, oversized, or overly complex query.
	ErrInvalidSearchQuery = errors.New("invalid search query")
)

type pageCursor struct {
	Kind        string   `json:"kind"`
	Scope       string   `json:"scope,omitempty"`
	Key         string   `json:"key"`
	ID          string   `json:"id"`
	Rank        *float64 `json:"rank,omitempty"`
	DiscNumber  *int     `json:"disc_number,omitempty"`
	TrackNumber *int     `json:"track_number,omitempty"`
}

func sortableTrackNumber(value *int) int {
	if value == nil {
		return math.MaxInt32
	}
	return *value
}

func preparePage(kind, scope string, options PageOptions) (int, *pageCursor, error) {
	limit := options.Limit
	if limit == 0 {
		limit = defaultPageLimit
	}
	if limit < 1 || limit > maxPageLimit {
		return 0, nil, fmt.Errorf("page limit must be between 1 and %d", maxPageLimit)
	}
	if strings.TrimSpace(options.Cursor) == "" {
		return limit, nil, nil
	}
	encoded := strings.TrimSpace(options.Cursor)
	if len(encoded) > maxCursorLength {
		return 0, nil, errors.New("invalid page cursor")
	}
	data, err := base64.RawURLEncoding.DecodeString(encoded)
	if err != nil {
		return 0, nil, errors.New("invalid page cursor")
	}
	var cursor pageCursor
	if err := json.Unmarshal(data, &cursor); err != nil || cursor.Kind != kind || cursor.Scope != scope || cursor.Key == "" || cursor.ID == "" {
		return 0, nil, errors.New("invalid page cursor")
	}
	return limit, &cursor, nil
}

func finishPage[T any](page *Page[T], limit int, cursor func(T) pageCursor) Page[T] {
	if page.Items == nil {
		page.Items = make([]T, 0)
	}
	if len(page.Items) <= limit {
		return *page
	}
	page.HasMore = true
	page.Items = page.Items[:limit]
	page.NextCursor = encodeCursor(cursor(page.Items[len(page.Items)-1]))
	return *page
}

func encodeCursor(cursor pageCursor) string {
	data, _ := json.Marshal(cursor)
	return base64.RawURLEncoding.EncodeToString(data)
}

// SearchDiagnostics reports how user search text is compiled for FTS5.
type SearchDiagnostics struct {
	Valid      bool
	InputBytes int
	Terms      []string
	MatchQuery string
	Scope      string
	Message    string
}

// DiagnoseSearchQuery validates and explains how search text is compiled.
func DiagnoseSearchQuery(value string) SearchDiagnostics {
	diag := SearchDiagnostics{InputBytes: len(value)}
	terms, err := parseSearchTerms(value)
	if err != nil {
		diag.Message = err.Error()
		return diag
	}
	matchQuery, scope := buildSearchMatchQuery(terms)
	diag.Valid = true
	diag.Terms = terms
	diag.MatchQuery = matchQuery
	diag.Scope = scope
	return diag
}

func compileSearchQuery(value string) (matchQuery, scope string, err error) {
	terms, err := parseSearchTerms(value)
	if err != nil {
		return "", "", err
	}
	matchQuery, scope = buildSearchMatchQuery(terms)
	return matchQuery, scope, nil
}

func parseSearchTerms(value string) ([]string, error) {
	if !utf8.ValidString(value) {
		return nil, fmt.Errorf("%w: query is not valid UTF-8", ErrInvalidSearchQuery)
	}
	if len(value) > maxSearchQueryBytes {
		return nil, fmt.Errorf("%w: query is too long", ErrInvalidSearchQuery)
	}
	terms := make([]string, 0, 4)
	current := make([]rune, 0, 16)
	flush := func() error {
		if len(current) == 0 {
			return nil
		}
		if len(current) > maxSearchTermRunes {
			return fmt.Errorf("%w: term is too long", ErrInvalidSearchQuery)
		}
		if len(terms) == maxSearchTerms {
			return fmt.Errorf("%w: too many terms", ErrInvalidSearchQuery)
		}
		terms = append(terms, string(current))
		current = current[:0]
		return nil
	}
	for _, char := range value {
		if unicode.IsLetter(char) || unicode.IsNumber(char) {
			current = append(current, unicode.ToLower(char))
			continue
		}
		if unicode.IsMark(char) && len(current) > 0 {
			current = append(current, unicode.ToLower(char))
			continue
		}
		if err := flush(); err != nil {
			return nil, err
		}
	}
	if err := flush(); err != nil {
		return nil, err
	}
	if len(terms) == 0 {
		return nil, fmt.Errorf("%w: query has no searchable terms", ErrInvalidSearchQuery)
	}
	return terms, nil
}

func buildSearchMatchQuery(terms []string) (matchQuery, scope string) {
	quoted := make([]string, len(terms))
	for index, term := range terms {
		quoted[index] = `"` + term + `"*`
	}
	matchQuery = strings.Join(quoted, " AND ")
	hash := sha256.Sum256([]byte(matchQuery))
	scope = base64.RawURLEncoding.EncodeToString(hash[:])
	return matchQuery, scope
}

func (r *QueryRepository) check(ctx context.Context) error {
	if r == nil || r.db == nil {
		return errors.New("query repository has no database")
	}
	if ctx == nil {
		return errors.New("query context must not be nil")
	}
	return ctx.Err()
}

func (r *QueryRepository) checkID(ctx context.Context, id string) error {
	if err := r.check(ctx); err != nil {
		return err
	}
	id = strings.TrimSpace(id)
	if id == "" {
		return errors.New("library item ID must not be empty")
	}
	if len(id) > maxOpaqueIDLength {
		return errors.New("library item ID is too long")
	}
	return nil
}

type rowScanner interface {
	Scan(dest ...any) error
}

func scanAlbum(row rowScanner) (Album, error) {
	var album Album
	var artistID, artistName, coverID sql.NullString
	var year sql.NullInt64
	if err := row.Scan(
		&album.ID,
		&album.Title,
		&artistID,
		&artistName,
		&year,
		&coverID,
		&album.TrackCount,
		&album.AddedAt,
	); err != nil {
		return album, err
	}
	album.ArtistID = nullStringPointer(artistID)
	album.ArtistName = nullStringPointer(artistName)
	album.Year = nullIntPointer(year)
	album.CoverID = nullStringPointer(coverID)
	return album, nil
}

func scanTrack(row rowScanner, extraDest ...any) (Track, error) {
	var track Track
	var artistID, artistName, albumID, albumTitle, albumArtistID, albumArtistName sql.NullString
	var genre, dateText, coverID sql.NullString
	var trackNumber, totalTracks, discNumber, totalDiscs, sampleRate, bitsPerSample, channels sql.NullInt64
	var durationMS sql.NullInt64
	destinations := []any{
		&track.ID, &track.Title, &track.Format,
		&artistID, &artistName, &albumID, &albumTitle,
		&albumArtistID, &albumArtistName, &genre, &dateText,
		&trackNumber, &totalTracks, &discNumber, &totalDiscs,
		&durationMS, &sampleRate, &bitsPerSample, &channels, &coverID,
	}
	destinations = append(destinations, extraDest...)
	if err := row.Scan(destinations...); err != nil {
		return track, err
	}
	track.ArtistID = nullStringPointer(artistID)
	track.ArtistName = nullStringPointer(artistName)
	track.AlbumID = nullStringPointer(albumID)
	track.AlbumTitle = nullStringPointer(albumTitle)
	track.AlbumArtistID = nullStringPointer(albumArtistID)
	track.AlbumArtistName = nullStringPointer(albumArtistName)
	track.Genre = nullStringPointer(genre)
	track.DateText = nullStringPointer(dateText)
	track.TrackNumber = nullIntPointer(trackNumber)
	track.TotalTracks = nullIntPointer(totalTracks)
	track.DiscNumber = nullIntPointer(discNumber)
	track.TotalDiscs = nullIntPointer(totalDiscs)
	track.DurationMS = nullInt64Pointer(durationMS)
	track.SampleRate = nullIntPointer(sampleRate)
	track.BitsPerSample = nullIntPointer(bitsPerSample)
	track.Channels = nullIntPointer(channels)
	track.CoverID = nullStringPointer(coverID)
	return track, nil
}

func nullStringPointer(value sql.NullString) *string {
	if !value.Valid {
		return nil
	}
	return &value.String
}

func nullIntPointer(value sql.NullInt64) *int {
	if !value.Valid {
		return nil
	}
	result := int(value.Int64)
	return &result
}

func nullInt64Pointer(value sql.NullInt64) *int64 {
	if !value.Valid {
		return nil
	}
	result := value.Int64
	return &result
}
