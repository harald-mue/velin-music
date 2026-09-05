package httpapi

import (
	"bytes"
	"context"
	"database/sql"
	"encoding/json"
	"image"
	"image/color"
	"image/png"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/harald-mue/velin-music/server/internal/auth"
	"github.com/harald-mue/velin-music/server/internal/db"
	"github.com/harald-mue/velin-music/server/internal/library"
)

func TestLibraryEndpointsRequireBearerToken(t *testing.T) {
	handler := openHTTPTestHandler(t)
	for _, path := range []string{
		"/api/v1/library/summary",
		"/api/v1/artists",
		"/api/v1/albums",
		"/api/v1/tracks",
		"/api/v1/search?q=test",
		"/api/v1/covers/test",
		"/api/v1/tracks/test/stream",
	} {
		req := httptest.NewRequest(http.MethodGet, path, nil)
		res := httptest.NewRecorder()
		handler.ServeHTTP(res, req)
		if res.Code != http.StatusUnauthorized {
			t.Fatalf("%s status = %d, want %d", path, res.Code, http.StatusUnauthorized)
		}
	}
}

func TestLibraryEndpointsListGetAndSearch(t *testing.T) {
	database, err := db.Open(context.Background(), filepath.Join(t.TempDir(), "velin.db"))
	if err != nil {
		t.Fatalf("open database: %v", err)
	}
	t.Cleanup(func() { _ = database.Close() })

	dataDir := t.TempDir()
	rootID := seedHTTPTestLibrary(t, database, dataDir)
	api, err := New(Config{Version: "test", DataDir: dataDir}, database)
	if err != nil {
		t.Fatalf("New() error = %v", err)
	}
	t.Cleanup(api.Stop)
	handler := api.Handler()

	tokens := auth.NewTokenRepository(database)
	issued, err := tokens.Issue(context.Background(), "Phone")
	if err != nil {
		t.Fatalf("Issue() error = %v", err)
	}

	authHeader := "Bearer " + issued.Token
	summaryReq := httptest.NewRequest(http.MethodGet, "/api/v1/library/summary", nil)
	summaryReq.Header.Set("Authorization", authHeader)
	summaryRes := httptest.NewRecorder()
	handler.ServeHTTP(summaryRes, summaryReq)
	var summary library.Summary
	if summaryRes.Code != http.StatusOK || json.NewDecoder(summaryRes.Body).Decode(&summary) != nil ||
		summary.ArtistCount != 2 || summary.AlbumCount != 2 || summary.TrackCount != 2 || summary.Revision == "" {
		t.Fatalf("summary = %d %+v", summaryRes.Code, summary)
	}

	artistsReq := httptest.NewRequest(http.MethodGet, "/api/v1/artists?limit=10", nil)
	artistsReq.Header.Set("Authorization", authHeader)
	artistsRes := httptest.NewRecorder()
	handler.ServeHTTP(artistsRes, artistsReq)
	if artistsRes.Code != http.StatusOK {
		t.Fatalf("artists status = %d, body = %s", artistsRes.Code, artistsRes.Body.String())
	}
	var artists library.Page[library.Artist]
	if err := json.NewDecoder(artistsRes.Body).Decode(&artists); err != nil {
		t.Fatalf("decode artists: %v", err)
	}
	if len(artists.Items) != 2 {
		t.Fatalf("artists = %+v", artists)
	}

	artistReq := httptest.NewRequest(http.MethodGet, "/api/v1/artists/"+artists.Items[0].ID, nil)
	artistReq.Header.Set("Authorization", authHeader)
	artistRes := httptest.NewRecorder()
	handler.ServeHTTP(artistRes, artistReq)
	if artistRes.Code != http.StatusOK {
		t.Fatalf("artist detail status = %d", artistRes.Code)
	}

	albumsReq := httptest.NewRequest(http.MethodGet, "/api/v1/albums?limit=10", nil)
	albumsReq.Header.Set("Authorization", authHeader)
	albumsRes := httptest.NewRecorder()
	handler.ServeHTTP(albumsRes, albumsReq)
	var albums library.Page[library.Album]
	if albumsRes.Code != http.StatusOK || json.NewDecoder(albumsRes.Body).Decode(&albums) != nil || len(albums.Items) != 2 {
		t.Fatalf("albums = %d %+v", albumsRes.Code, albums)
	}

	tracksReq := httptest.NewRequest(http.MethodGet, "/api/v1/tracks?album_id="+albums.Items[0].ID, nil)
	tracksReq.Header.Set("Authorization", authHeader)
	tracksRes := httptest.NewRecorder()
	handler.ServeHTTP(tracksRes, tracksReq)
	var tracks library.Page[library.Track]
	if tracksRes.Code != http.StatusOK || json.NewDecoder(tracksRes.Body).Decode(&tracks) != nil || len(tracks.Items) != 1 {
		t.Fatalf("tracks = %d %+v", tracksRes.Code, tracks)
	}
	if strings.Contains(tracksRes.Body.String(), "relative_path") {
		t.Fatalf("track response exposes source identity: %s", tracksRes.Body.String())
	}

	trackReq := httptest.NewRequest(http.MethodGet, "/api/v1/tracks/"+tracks.Items[0].ID, nil)
	trackReq.Header.Set("Authorization", authHeader)
	trackRes := httptest.NewRecorder()
	handler.ServeHTTP(trackRes, trackReq)
	if trackRes.Code != http.StatusOK {
		t.Fatalf("track detail status = %d", trackRes.Code)
	}

	searchReq := httptest.NewRequest(http.MethodGet, "/api/v1/search?q=alp&limit=10", nil)
	searchReq.Header.Set("Authorization", authHeader)
	searchRes := httptest.NewRecorder()
	handler.ServeHTTP(searchRes, searchReq)
	var search library.Page[library.Track]
	if searchRes.Code != http.StatusOK || json.NewDecoder(searchRes.Body).Decode(&search) != nil || len(search.Items) != 1 || search.Items[0].Title != "Alpha" {
		t.Fatalf("search = %d %+v", searchRes.Code, search)
	}

	missingReq := httptest.NewRequest(http.MethodGet, "/api/v1/artists/missing", nil)
	missingReq.Header.Set("Authorization", authHeader)
	missingRes := httptest.NewRecorder()
	handler.ServeHTTP(missingRes, missingReq)
	if missingRes.Code != http.StatusNotFound {
		t.Fatalf("missing artist status = %d, want %d", missingRes.Code, http.StatusNotFound)
	}

	emptySearchReq := httptest.NewRequest(http.MethodGet, "/api/v1/search", nil)
	emptySearchReq.Header.Set("Authorization", authHeader)
	emptySearchRes := httptest.NewRecorder()
	handler.ServeHTTP(emptySearchRes, emptySearchReq)
	if emptySearchRes.Code != http.StatusBadRequest {
		t.Fatalf("empty search status = %d, want %d", emptySearchRes.Code, http.StatusBadRequest)
	}

	_ = rootID
}

func TestCoverEndpointServesCachedArtwork(t *testing.T) {
	database, err := db.Open(context.Background(), filepath.Join(t.TempDir(), "velin.db"))
	if err != nil {
		t.Fatalf("open database: %v", err)
	}
	t.Cleanup(func() { _ = database.Close() })

	dataDir := t.TempDir()
	coverID := seedHTTPTestLibraryWithArtwork(t, database, dataDir)
	api, err := New(Config{Version: "test", DataDir: dataDir}, database)
	if err != nil {
		t.Fatalf("New() error = %v", err)
	}
	t.Cleanup(api.Stop)
	handler := api.Handler()

	tokens := auth.NewTokenRepository(database)
	issued, err := tokens.Issue(context.Background(), "Phone")
	if err != nil {
		t.Fatalf("Issue() error = %v", err)
	}

	req := httptest.NewRequest(http.MethodGet, "/api/v1/covers/"+coverID, nil)
	req.Header.Set("Authorization", "Bearer "+issued.Token)
	res := httptest.NewRecorder()
	handler.ServeHTTP(res, req)
	if res.Code != http.StatusOK {
		t.Fatalf("cover status = %d, body = %s", res.Code, res.Body.String())
	}
	if got := res.Header().Get("Content-Type"); got != "image/png" {
		t.Fatalf("content type = %q, want image/png", got)
	}
	if len(res.Body.Bytes()) == 0 {
		t.Fatal("cover body is empty")
	}
	if got := res.Header().Get("Cache-Control"); got != "private, max-age=31536000, immutable" {
		t.Fatalf("cache control = %q", got)
	}

	variantReq := httptest.NewRequest(http.MethodGet, "/api/v1/covers/"+coverID+"/128", nil)
	variantReq.Header.Set("Authorization", "Bearer "+issued.Token)
	variantRes := httptest.NewRecorder()
	handler.ServeHTTP(variantRes, variantReq)
	if variantRes.Code != http.StatusOK {
		t.Fatalf("cover variant status = %d, body = %s", variantRes.Code, variantRes.Body.String())
	}
	if got := variantRes.Header().Get("Content-Type"); got != "image/jpeg" {
		t.Fatalf("variant content type = %q, want image/jpeg", got)
	}
	if len(variantRes.Body.Bytes()) == 0 {
		t.Fatal("cover variant body is empty")
	}

	invalidReq := httptest.NewRequest(http.MethodGet, "/api/v1/covers/"+coverID+"/1024", nil)
	invalidReq.Header.Set("Authorization", "Bearer "+issued.Token)
	invalidRes := httptest.NewRecorder()
	handler.ServeHTTP(invalidRes, invalidReq)
	if invalidRes.Code != http.StatusBadRequest {
		t.Fatalf("invalid cover variant status = %d, want %d", invalidRes.Code, http.StatusBadRequest)
	}
}

func TestStreamEndpointServesByteRange(t *testing.T) {
	database, err := db.Open(context.Background(), filepath.Join(t.TempDir(), "velin.db"))
	if err != nil {
		t.Fatalf("open database: %v", err)
	}
	t.Cleanup(func() { _ = database.Close() })

	dataDir := t.TempDir()
	trackID, contents := seedHTTPTestStreamTrack(t, database, dataDir)
	api, err := New(Config{Version: "test", DataDir: dataDir}, database)
	if err != nil {
		t.Fatalf("New() error = %v", err)
	}
	t.Cleanup(api.Stop)
	handler := api.Handler()

	tokens := auth.NewTokenRepository(database)
	issued, err := tokens.Issue(context.Background(), "Phone")
	if err != nil {
		t.Fatalf("Issue() error = %v", err)
	}

	req := httptest.NewRequest(http.MethodGet, "/api/v1/tracks/"+trackID+"/stream", nil)
	req.Header.Set("Authorization", "Bearer "+issued.Token)
	req.Header.Set("Range", "bytes=0-3")
	res := httptest.NewRecorder()
	handler.ServeHTTP(res, req)
	if res.Code != http.StatusPartialContent {
		t.Fatalf("stream status = %d, body = %s", res.Code, res.Body.String())
	}
	if got := res.Header().Get("Content-Type"); got != "audio/flac" {
		t.Fatalf("content type = %q, want audio/flac", got)
	}
	if got := res.Body.Bytes(); len(got) != 4 || !bytes.Equal(got, contents[:4]) {
		t.Fatalf("range body = %q, want first 4 bytes", got)
	}
}

func seedHTTPTestStreamTrack(t *testing.T, database *sql.DB, dataDir string) (string, []byte) {
	t.Helper()
	rootPath := filepath.Join(t.TempDir(), "music")
	if err := os.Mkdir(rootPath, 0o755); err != nil {
		t.Fatalf("create test root: %v", err)
	}
	root, err := library.NewStore(database).AddRoot(context.Background(), rootPath)
	if err != nil {
		t.Fatalf("AddRoot() error = %v", err)
	}
	relative := "track.flac"
	path := filepath.Join(rootPath, relative)
	contents := testHTTPFLACBytes()
	if err := os.WriteFile(path, contents, 0o600); err != nil {
		t.Fatalf("write FLAC: %v", err)
	}
	info, err := os.Stat(path)
	if err != nil {
		t.Fatalf("stat FLAC: %v", err)
	}
	scan, err := library.NewTrackRepository(database).BeginScan(context.Background(), root.ID)
	if err != nil {
		t.Fatalf("BeginScan() error = %v", err)
	}
	if err := scan.Upsert(context.Background(), library.MediaFile{
		RelativePath: relative,
		Format:       library.FormatFLAC,
		Size:         info.Size(),
		ModifiedAt:   info.ModTime(),
	}, library.TrackMetadata{
		Format: library.FormatFLAC, Title: "Track", Artist: "Artist", AlbumArtist: "Artist", Album: "Album",
	}); err != nil {
		t.Fatalf("Upsert() error = %v", err)
	}
	if err := scan.Finish(context.Background()); err != nil {
		t.Fatalf("Finish() error = %v", err)
	}
	var trackID string
	if err := database.QueryRow("SELECT id FROM tracks WHERE root_id = ?", root.ID).Scan(&trackID); err != nil {
		t.Fatalf("read track id: %v", err)
	}
	return trackID, contents
}

func testHTTPFLACBytes() []byte {
	// Minimal FLAC header sufficient for streaming tests.
	return []byte("fLaC\x00\x00\x00\x22" + string(make([]byte, 30)))
}

func seedHTTPTestLibrary(t *testing.T, database *sql.DB, dataDir string) string {
	t.Helper()
	rootPath := filepath.Join(t.TempDir(), "music")
	if err := os.Mkdir(rootPath, 0o755); err != nil {
		t.Fatalf("create test root: %v", err)
	}
	root, err := library.NewStore(database).AddRoot(context.Background(), rootPath)
	if err != nil {
		t.Fatalf("AddRoot() error = %v", err)
	}
	scan, err := library.NewTrackRepository(database).BeginScan(context.Background(), root.ID)
	if err != nil {
		t.Fatalf("BeginScan() error = %v", err)
	}
	tracks := []struct {
		path, title, artist, album string
	}{
		{"alpha.flac", "Alpha", "Alpha", "Alpha Album"},
		{"beta.flac", "Beta", "Beta", "Beta Album"},
	}
	for _, value := range tracks {
		if err := scan.Upsert(context.Background(), library.MediaFile{RelativePath: value.path, Format: library.FormatFLAC, Size: 1}, library.TrackMetadata{
			Format: library.FormatFLAC, Title: value.title, Artist: value.artist, AlbumArtist: value.artist,
			Album: value.album,
		}); err != nil {
			t.Fatalf("Upsert(%s) error = %v", value.path, err)
		}
	}
	if err := scan.Finish(context.Background()); err != nil {
		t.Fatalf("Finish() error = %v", err)
	}
	return root.ID
}

func seedHTTPTestLibraryWithArtwork(t *testing.T, database *sql.DB, dataDir string) string {
	t.Helper()
	rootPath := filepath.Join(t.TempDir(), "music")
	if err := os.Mkdir(rootPath, 0o755); err != nil {
		t.Fatalf("create test root: %v", err)
	}
	root, err := library.NewStore(database).AddRoot(context.Background(), rootPath)
	if err != nil {
		t.Fatalf("AddRoot() error = %v", err)
	}
	cache, err := library.NewArtworkCache(dataDir)
	if err != nil {
		t.Fatalf("NewArtworkCache() error = %v", err)
	}
	repository := library.NewTrackRepositoryWithArtwork(database, library.NewCoverStore(database, cache))
	scan, err := repository.BeginScan(context.Background(), root.ID)
	if err != nil {
		t.Fatalf("BeginScan() error = %v", err)
	}
	if err := scan.Upsert(context.Background(), library.MediaFile{
		RelativePath: "cover.flac", Format: library.FormatFLAC, Size: 10,
	}, library.TrackMetadata{
		Format: library.FormatFLAC, Title: "Covered", Artist: "Artist", AlbumArtist: "Artist", Album: "Album",
		Artwork: testPNGBytes(), ArtworkMIME: "image/png",
	}); err != nil {
		t.Fatalf("Upsert() error = %v", err)
	}
	if err := scan.Finish(context.Background()); err != nil {
		t.Fatalf("Finish() error = %v", err)
	}
	var coverID string
	if err := database.QueryRow("SELECT cover_id FROM tracks WHERE root_id = ?", root.ID).Scan(&coverID); err != nil {
		t.Fatalf("read cover id: %v", err)
	}
	return coverID
}

func testPNGBytes() []byte {
	picture := image.NewRGBA(image.Rect(0, 0, 2, 2))
	picture.Set(0, 0, color.RGBA{R: 220, G: 40, B: 80, A: 255})
	var encoded bytes.Buffer
	if err := png.Encode(&encoded, picture); err != nil {
		panic(err)
	}
	return encoded.Bytes()
}
