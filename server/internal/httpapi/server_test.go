package httpapi

import (
	"bytes"
	"context"
	"encoding/json"
	"image"
	"image/color"
	"image/png"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"testing"
	"time"

	"github.com/harald-mue/velin-music/server/internal/auth"
	"github.com/harald-mue/velin-music/server/internal/db"
	"github.com/harald-mue/velin-music/server/internal/library"
)

func openHTTPTestServer(t *testing.T) *Server {
	t.Helper()
	dataDir := t.TempDir()
	database, err := db.Open(context.Background(), filepath.Join(dataDir, "velin.db"))
	if err != nil {
		t.Fatalf("open http test database: %v", err)
	}
	t.Cleanup(func() { _ = database.Close() })
	api, err := New(Config{Version: "test", DataDir: dataDir}, database)
	if err != nil {
		t.Fatalf("New() error = %v", err)
	}
	t.Cleanup(api.Stop)
	return api
}

func openHTTPTestHandler(t *testing.T) http.Handler {
	t.Helper()
	return openHTTPTestServer(t).Handler()
}

func TestStatus(t *testing.T) {
	handler := openHTTPTestHandler(t)
	req := httptest.NewRequest(http.MethodGet, "/api/v1/status", nil)
	res := httptest.NewRecorder()

	handler.ServeHTTP(res, req)

	if res.Code != http.StatusOK {
		t.Fatalf("status code = %d, want %d", res.Code, http.StatusOK)
	}
	if got := res.Header().Get("Content-Type"); got != "application/json; charset=utf-8" {
		t.Fatalf("content type = %q", got)
	}

	var body statusResponse
	if err := json.NewDecoder(res.Body).Decode(&body); err != nil {
		t.Fatalf("decode response: %v", err)
	}
	want := statusResponse{Name: "Velin", Status: "ok", Version: "test"}
	if body != want {
		t.Fatalf("body = %+v, want %+v", body, want)
	}
}

func TestPairExchangesValidCode(t *testing.T) {
	database, err := db.Open(context.Background(), filepath.Join(t.TempDir(), "velin.db"))
	if err != nil {
		t.Fatalf("open database: %v", err)
	}
	t.Cleanup(func() { _ = database.Close() })

	pairing := auth.NewPairingRepository(database)
	created, err := pairing.Create(context.Background(), "Pixel")
	if err != nil {
		t.Fatalf("Create() error = %v", err)
	}

	api, err := New(Config{Version: "test", DataDir: t.TempDir()}, database)
	if err != nil {
		t.Fatalf("New() error = %v", err)
	}
	t.Cleanup(api.Stop)
	body, err := json.Marshal(pairRequest{Code: created.Code})
	if err != nil {
		t.Fatalf("marshal pair request: %v", err)
	}
	req := httptest.NewRequest(http.MethodPost, "/api/v1/pair", bytes.NewReader(body))
	res := httptest.NewRecorder()
	api.Handler().ServeHTTP(res, req)

	if res.Code != http.StatusOK {
		t.Fatalf("status code = %d, want %d, body = %s", res.Code, http.StatusOK, res.Body.String())
	}
	var response pairResponse
	if err := json.NewDecoder(res.Body).Decode(&response); err != nil {
		t.Fatalf("decode response: %v", err)
	}
	if response.DeviceID == "" || response.Token == "" || response.Server.Version != "test" {
		t.Fatalf("pair response = %+v", response)
	}
}

func TestProtectedRequiresBearerToken(t *testing.T) {
	database, err := db.Open(context.Background(), filepath.Join(t.TempDir(), "velin.db"))
	if err != nil {
		t.Fatalf("open database: %v", err)
	}
	t.Cleanup(func() { _ = database.Close() })

	tokens := auth.NewTokenRepository(database)
	protected := Protected(tokens, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNoContent)
	}))

	req := httptest.NewRequest(http.MethodGet, "/protected", nil)
	res := httptest.NewRecorder()
	protected.ServeHTTP(res, req)
	if res.Code != http.StatusUnauthorized {
		t.Fatalf("status = %d, want %d", res.Code, http.StatusUnauthorized)
	}
}

func TestNewPrewarmsReferencedArtwork(t *testing.T) {
	dataDir := t.TempDir()
	database, err := db.Open(context.Background(), filepath.Join(dataDir, "velin.db"))
	if err != nil {
		t.Fatalf("open database: %v", err)
	}
	t.Cleanup(func() { _ = database.Close() })
	cache, err := library.NewArtworkCache(dataDir)
	if err != nil {
		t.Fatalf("NewArtworkCache() error = %v", err)
	}
	cover, err := library.NewCoverStore(database, cache).Store(context.Background(), serverTestPNG(), "image/png")
	if err != nil {
		t.Fatalf("store cover: %v", err)
	}
	if _, err := database.Exec(
		"INSERT INTO albums (id, title, cover_id) VALUES ('album', 'Album', ?)",
		cover.ID,
	); err != nil {
		t.Fatalf("insert album: %v", err)
	}

	api, err := New(Config{Version: "test", DataDir: dataDir}, database)
	if err != nil {
		t.Fatalf("New() error = %v", err)
	}
	t.Cleanup(api.Stop)

	deadline := time.Now().Add(3 * time.Second)
	for _, size := range []int{256, 512} {
		path := filepath.Join(dataDir, "covers", "variants", cover.ID+"-"+strconv.Itoa(size)+".jpg")
		for {
			if _, err := os.Stat(path); err == nil {
				break
			}
			if time.Now().After(deadline) {
				t.Fatalf("timed out waiting for %d px startup variant", size)
			}
			time.Sleep(10 * time.Millisecond)
		}
	}
	api.Stop()
}

func serverTestPNG() []byte {
	imageData := image.NewRGBA(image.Rect(0, 0, 4, 4))
	for y := range 4 {
		for x := range 4 {
			imageData.SetRGBA(x, y, color.RGBA{R: 20, G: 40, B: 60, A: 255})
		}
	}
	var buffer bytes.Buffer
	if err := png.Encode(&buffer, imageData); err != nil {
		panic(err)
	}
	return buffer.Bytes()
}
