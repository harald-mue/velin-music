package httpapi

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"testing"

	"github.com/harald-mue/velin-music/server/internal/auth"
	"github.com/harald-mue/velin-music/server/internal/db"
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
