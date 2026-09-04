package library

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"path/filepath"
	"strings"
	"testing"
)

func TestQueryRepositoryListsWithOpaqueKeysetPagination(t *testing.T) {
	database := openLibraryTestDB(t)
	rootID := addTestRoot(t, database, filepath.Join(t.TempDir(), "music"))
	seedQueryTracks(t, database, rootID)
	repository := NewQueryRepository(database)
	ctx := context.Background()

	artists, err := repository.ListArtists(ctx, PageOptions{Limit: 1})
	if err != nil {
		t.Fatalf("ListArtists() error = %v", err)
	}
	if len(artists.Items) != 1 || !artists.HasMore || artists.NextCursor == "" || artists.Items[0].Name != "Alpha" {
		t.Fatalf("first artists page = %+v", artists)
	}
	if strings.Contains(artists.NextCursor, "Alpha") {
		t.Fatal("artist cursor is not opaque")
	}
	nextArtists, err := repository.ListArtists(ctx, PageOptions{Limit: 1, Cursor: artists.NextCursor})
	if err != nil {
		t.Fatalf("ListArtists() next page error = %v", err)
	}
	if len(nextArtists.Items) != 1 || nextArtists.Items[0].Name != "Beta" || nextArtists.NextCursor == "" {
		t.Fatalf("second artists page = %+v", nextArtists)
	}
	lastArtists, err := repository.ListArtists(ctx, PageOptions{Limit: 1, Cursor: nextArtists.NextCursor})
	if err != nil {
		t.Fatalf("ListArtists() last page error = %v", err)
	}
	if len(lastArtists.Items) != 1 || lastArtists.Items[0].Name != "Zeta" || lastArtists.HasMore || lastArtists.NextCursor != "" {
		t.Fatalf("last artists page = %+v", lastArtists)
	}
	if lastArtists.Items[0].AlbumCount != 1 || lastArtists.Items[0].TrackCount != 1 {
		t.Fatalf("artist counts = %+v", lastArtists.Items[0])
	}

	albums, err := repository.ListAlbums(ctx, PageOptions{Limit: 10})
	if err != nil {
		t.Fatalf("ListAlbums() error = %v", err)
	}
	if len(albums.Items) != 3 || albums.HasMore {
		t.Fatalf("albums page = %+v", albums)
	}
	if albums.Items[0].Title != "Alpha Album" || albums.Items[0].ArtistName == nil || *albums.Items[0].ArtistName != "Alpha" || albums.Items[0].TrackCount != 1 {
		t.Fatalf("first album = %+v", albums.Items[0])
	}

	tracks, err := repository.ListTracks(ctx, TrackListOptions{PageOptions: PageOptions{Limit: 2}})
	if err != nil {
		t.Fatalf("ListTracks() error = %v", err)
	}
	if len(tracks.Items) != 2 || !tracks.HasMore || tracks.NextCursor == "" {
		t.Fatalf("first tracks page = %+v", tracks)
	}
	nextTracks, err := repository.ListTracks(ctx, TrackListOptions{PageOptions: PageOptions{Limit: 2, Cursor: tracks.NextCursor}})
	if err != nil {
		t.Fatalf("ListTracks() next page error = %v", err)
	}
	if len(nextTracks.Items) != 1 || nextTracks.HasMore || nextTracks.Items[0].Title != "Zulu" {
		t.Fatalf("last tracks page = %+v", nextTracks)
	}
	if _, err := repository.ListTracks(ctx, TrackListOptions{
		PageOptions: PageOptions{Limit: 2, Cursor: tracks.NextCursor},
		AlbumID:     albums.Items[0].ID,
	}); err == nil {
		t.Fatal("ListTracks() accepted cursor with changed filter scope")
	}
	filtered, err := repository.ListTracks(ctx, TrackListOptions{AlbumID: albums.Items[0].ID})
	if err != nil {
		t.Fatalf("ListTracks() filtered error = %v", err)
	}
	if len(filtered.Items) != 1 || filtered.Items[0].AlbumID == nil || *filtered.Items[0].AlbumID != albums.Items[0].ID {
		t.Fatalf("filtered tracks = %+v", filtered)
	}

	encoded, err := json.Marshal(tracks.Items[0])
	if err != nil {
		t.Fatalf("marshal public track: %v", err)
	}
	if strings.Contains(string(encoded), "relative_path") || strings.Contains(string(encoded), "root_id") || strings.Contains(string(encoded), "file_size") {
		t.Fatalf("public track exposes source identity: %s", encoded)
	}
}

func TestQueryRepositoryAlbumTracksUseDiscAndTrackOrderAcrossPages(t *testing.T) {
	database := openLibraryTestDB(t)
	rootID := addTestRoot(t, database, filepath.Join(t.TempDir(), "music"))
	scan, err := NewTrackRepository(database).BeginScan(context.Background(), rootID)
	if err != nil {
		t.Fatalf("BeginScan() error = %v", err)
	}
	tracks := []struct {
		path, title string
		disc, track int
	}{
		{"disc2.flac", "A second disc", 2, 1},
		{"track2.flac", "A later title", 1, 2},
		{"track1.flac", "Z first title", 1, 1},
		{"unknown.flac", "Unknown position", 0, 0},
	}
	for _, item := range tracks {
		if err := scan.Upsert(context.Background(), MediaFile{RelativePath: item.path, Format: FormatFLAC, Size: 1}, TrackMetadata{
			Format: FormatFLAC, Title: item.title, Album: "Ordered Album", DiscNumber: item.disc, TrackNumber: item.track,
		}); err != nil {
			t.Fatalf("Upsert(%s) error = %v", item.path, err)
		}
	}
	if err := scan.Finish(context.Background()); err != nil {
		t.Fatalf("Finish() error = %v", err)
	}

	repository := NewQueryRepository(database)
	albums, err := repository.ListAlbums(context.Background(), PageOptions{Limit: 10})
	if err != nil || len(albums.Items) != 1 {
		t.Fatalf("ListAlbums() = %+v, %v", albums, err)
	}
	first, err := repository.ListTracks(context.Background(), TrackListOptions{
		PageOptions: PageOptions{Limit: 2}, AlbumID: albums.Items[0].ID,
	})
	if err != nil {
		t.Fatalf("ListTracks(first) error = %v", err)
	}
	second, err := repository.ListTracks(context.Background(), TrackListOptions{
		PageOptions: PageOptions{Limit: 2, Cursor: first.NextCursor}, AlbumID: albums.Items[0].ID,
	})
	if err != nil {
		t.Fatalf("ListTracks(second) error = %v", err)
	}
	got := []string{first.Items[0].Title, first.Items[1].Title, second.Items[0].Title, second.Items[1].Title}
	want := []string{"Z first title", "A later title", "A second disc", "Unknown position"}
	for index := range want {
		if got[index] != want[index] {
			t.Fatalf("album order = %v, want %v", got, want)
		}
	}
	if !first.HasMore || first.NextCursor == "" || second.HasMore {
		t.Fatalf("album pages = first %+v, second %+v", first, second)
	}
}

func TestQueryRepositoryTrackPaginationUsesIDTieBreaker(t *testing.T) {
	database := openLibraryTestDB(t)
	rootID := addTestRoot(t, database, filepath.Join(t.TempDir(), "music"))
	scan, err := NewTrackRepository(database).BeginScan(context.Background(), rootID)
	if err != nil {
		t.Fatalf("BeginScan() error = %v", err)
	}
	for _, path := range []string{"one.flac", "two.flac", "three.flac"} {
		if err := scan.Upsert(context.Background(), MediaFile{RelativePath: path, Format: FormatFLAC, Size: 1}, TrackMetadata{
			Format: FormatFLAC, Title: "Same title",
		}); err != nil {
			t.Fatalf("Upsert(%s) error = %v", path, err)
		}
	}
	if err := scan.Finish(context.Background()); err != nil {
		t.Fatalf("Finish() error = %v", err)
	}

	repository := NewQueryRepository(database)
	seen := make(map[string]bool)
	cursor := ""
	for {
		page, err := repository.ListTracks(context.Background(), TrackListOptions{PageOptions: PageOptions{Limit: 1, Cursor: cursor}})
		if err != nil {
			t.Fatalf("ListTracks() error = %v", err)
		}
		if len(page.Items) != 1 || seen[page.Items[0].ID] {
			t.Fatalf("track page = %+v, seen = %+v", page, seen)
		}
		seen[page.Items[0].ID] = true
		if !page.HasMore {
			break
		}
		cursor = page.NextCursor
	}
	if len(seen) != 3 {
		t.Fatalf("paginated track count = %d, want 3", len(seen))
	}
}

func TestQueryRepositoryArtistFilterIncludesAlbumArtistTracks(t *testing.T) {
	database := openLibraryTestDB(t)
	rootID := addTestRoot(t, database, filepath.Join(t.TempDir(), "music"))
	scan, err := NewTrackRepository(database).BeginScan(context.Background(), rootID)
	if err != nil {
		t.Fatalf("BeginScan() error = %v", err)
	}
	if err := scan.Upsert(context.Background(), MediaFile{RelativePath: "guest.flac", Format: FormatFLAC, Size: 1}, TrackMetadata{
		Format: FormatFLAC, Title: "Guest track", Artist: "Guest", AlbumArtist: "Main", Album: "Album",
	}); err != nil {
		t.Fatalf("Upsert() error = %v", err)
	}
	if err := scan.Finish(context.Background()); err != nil {
		t.Fatalf("Finish() error = %v", err)
	}
	repository := NewQueryRepository(database)
	artists, err := repository.ListArtists(context.Background(), PageOptions{})
	if err != nil {
		t.Fatalf("ListArtists() error = %v", err)
	}
	var mainID string
	for _, artist := range artists.Items {
		if artist.Name == "Main" {
			mainID = artist.ID
		}
	}
	if mainID == "" {
		t.Fatal("album artist is missing from artist list")
	}
	tracks, err := repository.ListTracks(context.Background(), TrackListOptions{ArtistID: mainID})
	if err != nil {
		t.Fatalf("ListTracks(album artist) error = %v", err)
	}
	if len(tracks.Items) != 1 || tracks.Items[0].Title != "Guest track" {
		t.Fatalf("album-artist tracks = %+v", tracks.Items)
	}
}

func TestQueryRepositorySearchTracksRanksAndPaginates(t *testing.T) {
	database := openLibraryTestDB(t)
	rootID := addTestRoot(t, database, filepath.Join(t.TempDir(), "music"))
	scan, err := NewTrackRepository(database).BeginScan(context.Background(), rootID)
	if err != nil {
		t.Fatalf("BeginScan() error = %v", err)
	}
	values := []struct {
		path, title, artist, album, genre string
	}{
		{"title.flac", "Needle Song", "Hay", "Stack", "Rock"},
		{"artist.flac", "Quiet Artist Song", "Needle Artist", "Stack", "Rock"},
		{"album.flac", "Quiet Album Song", "Hay", "Needle Album", "Rock"},
		{"other.flac", "Unrelated", "Else", "Different", "Jazz"},
	}
	for _, value := range values {
		if err := scan.Upsert(context.Background(), MediaFile{RelativePath: value.path, Format: FormatFLAC, Size: 1}, TrackMetadata{
			Format: FormatFLAC, Title: value.title, Artist: value.artist, AlbumArtist: value.artist,
			Album: value.album, Genre: value.genre,
		}); err != nil {
			t.Fatalf("Upsert(%s) error = %v", value.path, err)
		}
	}
	if err := scan.Finish(context.Background()); err != nil {
		t.Fatalf("Finish() error = %v", err)
	}

	repository := NewQueryRepository(database)
	first, err := repository.SearchTracks(context.Background(), "nee", PageOptions{Limit: 1})
	if err != nil {
		t.Fatalf("SearchTracks() error = %v", err)
	}
	if len(first.Items) != 1 || first.Items[0].Title != "Needle Song" || !first.HasMore || first.NextCursor == "" {
		t.Fatalf("first search page = %+v", first)
	}
	seen := map[string]bool{first.Items[0].ID: true}
	cursor := first.NextCursor
	for cursor != "" {
		page, err := repository.SearchTracks(context.Background(), "NEE", PageOptions{Limit: 1, Cursor: cursor})
		if err != nil {
			t.Fatalf("SearchTracks() next page error = %v", err)
		}
		if len(page.Items) != 1 || seen[page.Items[0].ID] {
			t.Fatalf("search page = %+v, seen = %+v", page, seen)
		}
		seen[page.Items[0].ID] = true
		cursor = page.NextCursor
	}
	if len(seen) != 3 {
		t.Fatalf("search result count = %d, want 3", len(seen))
	}
	if _, err := repository.SearchTracks(context.Background(), "hay", PageOptions{Limit: 1, Cursor: first.NextCursor}); err == nil {
		t.Fatal("SearchTracks() accepted cursor from a different query")
	}

	empty, err := repository.SearchTracks(context.Background(), "missing", PageOptions{})
	if err != nil {
		t.Fatalf("SearchTracks(missing) error = %v", err)
	}
	if empty.Items == nil || len(empty.Items) != 0 || empty.HasMore {
		t.Fatalf("empty search page = %+v", empty)
	}
	literal, err := repository.SearchTracks(context.Background(), `needle OR "unrelated"`, PageOptions{})
	if err != nil {
		t.Fatalf("SearchTracks(raw syntax) error = %v", err)
	}
	if len(literal.Items) != 0 {
		t.Fatalf("raw FTS syntax was interpreted as operators: %+v", literal.Items)
	}
}

func TestQueryRepositorySearchTracksRejectsUnsafeOrUnboundedQueries(t *testing.T) {
	repository := NewQueryRepository(openLibraryTestDB(t))
	for _, query := range []string{
		"   ---   ",
		"\u0301",
		string([]byte{0xff, 'a'}),
		strings.Repeat("x", maxSearchTermRunes+1),
		strings.Repeat("word ", maxSearchTerms+1),
		strings.Repeat("x", maxSearchQueryBytes+1),
	} {
		if _, err := repository.SearchTracks(context.Background(), query, PageOptions{}); !errors.Is(err, ErrInvalidSearchQuery) {
			t.Fatalf("SearchTracks(%q) error = %v, want ErrInvalidSearchQuery", query, err)
		}
	}

	match, _, err := compileSearchQuery(`needle OR "other"`)
	if err != nil {
		t.Fatalf("compileSearchQuery() error = %v", err)
	}
	if match != `"needle"* AND "or"* AND "other"*` {
		t.Fatalf("compiled FTS query = %q", match)
	}
	_, scope, err := compileSearchQuery("needle")
	if err != nil {
		t.Fatalf("compile cursor scope: %v", err)
	}
	diag := DiagnoseSearchQuery("needle")
	if !diag.Valid || diag.MatchQuery != `"needle"*` || diag.Scope != scope || len(diag.Terms) != 1 || diag.Terms[0] != "needle" {
		t.Fatalf("DiagnoseSearchQuery() = %+v", diag)
	}
	cursor := encodeCursor(pageCursor{Kind: "search_tracks", Scope: scope, Key: "x", ID: "y"})
	if _, err := repository.SearchTracks(context.Background(), "needle", PageOptions{Cursor: cursor}); err == nil {
		t.Fatal("SearchTracks() accepted cursor without rank")
	}
}

func TestQueryRepositoryGetsDetailsAndRejectsInvalidCursors(t *testing.T) {
	database := openLibraryTestDB(t)
	rootID := addTestRoot(t, database, filepath.Join(t.TempDir(), "music"))
	seedQueryTracks(t, database, rootID)
	repository := NewQueryRepository(database)
	ctx := context.Background()

	artists, err := repository.ListArtists(ctx, PageOptions{Limit: 10})
	if err != nil {
		t.Fatalf("ListArtists() error = %v", err)
	}
	artist, err := repository.GetArtist(ctx, artists.Items[0].ID)
	if err != nil || artist.Name != artists.Items[0].Name {
		t.Fatalf("GetArtist() = %+v, error %v", artist, err)
	}
	if _, err := repository.GetArtist(ctx, "missing"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("GetArtist(missing) error = %v, want ErrNotFound", err)
	}

	albums, err := repository.ListAlbums(ctx, PageOptions{Limit: 10})
	if err != nil {
		t.Fatalf("ListAlbums() error = %v", err)
	}
	album, err := repository.GetAlbum(ctx, albums.Items[0].ID)
	if err != nil || album.Title != albums.Items[0].Title || album.TrackCount != 1 {
		t.Fatalf("GetAlbum() = %+v, error %v", album, err)
	}
	if _, err := repository.GetAlbum(ctx, "missing"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("GetAlbum(missing) error = %v, want ErrNotFound", err)
	}

	tracks, err := repository.ListTracks(ctx, TrackListOptions{PageOptions: PageOptions{Limit: 10}})
	if err != nil {
		t.Fatalf("ListTracks() error = %v", err)
	}
	track, err := repository.GetTrack(ctx, tracks.Items[0].ID)
	if err != nil || track.Title != tracks.Items[0].Title || track.ArtistName == nil {
		t.Fatalf("GetTrack() = %+v, error %v", track, err)
	}
	if _, err := repository.GetTrack(ctx, "missing"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("GetTrack(missing) error = %v, want ErrNotFound", err)
	}

	for _, cursor := range []string{"not-base64", "eyJraW5kIjoiYWxidW1zIiwia2V5IjoieCIsImlkIjoieSJ9"} {
		if _, err := repository.ListArtists(ctx, PageOptions{Cursor: cursor}); err == nil {
			t.Fatalf("ListArtists(cursor %q) error = nil", cursor)
		}
	}
	if _, err := repository.ListTracks(ctx, TrackListOptions{PageOptions: PageOptions{Limit: maxPageLimit + 1}}); err == nil {
		t.Fatal("ListTracks(over-limit) error = nil")
	}
	if _, err := repository.GetTrack(ctx, " "); err == nil {
		t.Fatal("GetTrack(empty ID) error = nil")
	}
	if _, err := repository.GetTrack(ctx, strings.Repeat("x", maxOpaqueIDLength+1)); err == nil {
		t.Fatal("GetTrack(overlong ID) error = nil")
	}
	if _, err := repository.ListAlbums(ctx, PageOptions{Cursor: strings.Repeat("x", maxCursorLength+1)}); err == nil {
		t.Fatal("ListAlbums(overlong cursor) error = nil")
	}
}

func seedQueryTracks(t *testing.T, database *sql.DB, rootID string) {
	t.Helper()
	repository := NewTrackRepository(database)
	scan, err := repository.BeginScan(context.Background(), rootID)
	if err != nil {
		t.Fatalf("BeginScan() error = %v", err)
	}
	tracks := []struct {
		path, title, artist, album string
	}{
		{"alpha.flac", "Alpha", "Alpha", "Alpha Album"},
		{"beta.mp3", "Beta", "Beta", "Beta Album"},
		{"zeta.flac", "Zulu", "Zeta", "Zeta Album"},
	}
	for index, value := range tracks {
		format := FormatFLAC
		if index == 1 {
			format = FormatMP3
		}
		if err := scan.Upsert(context.Background(), MediaFile{RelativePath: value.path, Format: format, Size: int64(index + 1)}, TrackMetadata{
			Format: format, Title: value.title, Artist: value.artist, AlbumArtist: value.artist,
			Album: value.album, TrackNumber: index + 1,
		}); err != nil {
			t.Fatalf("Upsert(%s) error = %v", value.path, err)
		}
	}
	if err := scan.Finish(context.Background()); err != nil {
		t.Fatalf("Finish() error = %v", err)
	}
}
