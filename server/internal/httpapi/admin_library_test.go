package httpapi

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"time"

	"github.com/harald-mue/velin-music/server/internal/auth"
	"github.com/harald-mue/velin-music/server/internal/db"
)

func TestAdminLibraryRootsAndScans(t *testing.T) {
	rootDir := t.TempDir()
	database, err := db.Open(context.Background(), filepath.Join(t.TempDir(), "velin.db"))
	if err != nil {
		t.Fatalf("open database: %v", err)
	}
	t.Cleanup(func() { _ = database.Close() })

	api, err := New(Config{
		Version:   "test",
		PublicURL: "http://velin.local:8080",
		DataDir:   t.TempDir(),
	}, database)
	if err != nil {
		t.Fatalf("New() error = %v", err)
	}
	t.Cleanup(api.Stop)
	handler := api.Handler()

	setupBody, _ := json.Marshal(adminCredentialsRequest{
		Username: "admin",
		Password: strings.Repeat("x", 8),
	})
	setupReq := httptest.NewRequest(http.MethodPost, "/api/v1/admin/setup", bytes.NewReader(setupBody))
	setupRes := httptest.NewRecorder()
	handler.ServeHTTP(setupRes, setupReq)
	var session adminSessionResponse
	if setupRes.Code != http.StatusCreated || json.NewDecoder(setupRes.Body).Decode(&session) != nil || session.CSRFToken == "" {
		t.Fatalf("setup response = %d %+v", setupRes.Code, session)
	}
	cookie := setupRes.Result().Cookies()[0]

	listReq := httptest.NewRequest(http.MethodGet, "/api/v1/admin/library-roots", nil)
	listReq.AddCookie(cookie)
	listRes := httptest.NewRecorder()
	handler.ServeHTTP(listRes, listReq)
	var emptyRoots libraryRootListResponse
	if listRes.Code != http.StatusOK || json.NewDecoder(listRes.Body).Decode(&emptyRoots) != nil || len(emptyRoots.Items) != 0 {
		t.Fatalf("empty roots = %d %+v", listRes.Code, emptyRoots)
	}

	createBody, _ := json.Marshal(createLibraryRootRequest{Path: rootDir})
	createReq := httptest.NewRequest(http.MethodPost, "/api/v1/admin/library-roots", bytes.NewReader(createBody))
	createReq.AddCookie(cookie)
	createReq.Header.Set(auth.AdminCSRFHeader, session.CSRFToken)
	createRes := httptest.NewRecorder()
	handler.ServeHTTP(createRes, createReq)
	var created libraryRootResponse
	if createRes.Code != http.StatusCreated || json.NewDecoder(createRes.Body).Decode(&created) != nil || created.Path != rootDir {
		t.Fatalf("create root = %d %+v", createRes.Code, created)
	}

	scanReq := httptest.NewRequest(http.MethodPost, "/api/v1/admin/library-roots/"+created.ID+"/scan", nil)
	scanReq.AddCookie(cookie)
	scanReq.Header.Set(auth.AdminCSRFHeader, session.CSRFToken)
	scanRes := httptest.NewRecorder()
	handler.ServeHTTP(scanRes, scanReq)
	if scanRes.Code != http.StatusAccepted {
		t.Fatalf("start scan status = %d", scanRes.Code)
	}

	scansReq := httptest.NewRequest(http.MethodGet, "/api/v1/admin/scans?limit=5", nil)
	scansReq.AddCookie(cookie)
	var scans scanRunListResponse
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		scansRes := httptest.NewRecorder()
		handler.ServeHTTP(scansRes, scansReq)
		if scansRes.Code == http.StatusOK && json.NewDecoder(scansRes.Body).Decode(&scans) == nil && len(scans.Items) > 0 {
			break
		}
		time.Sleep(10 * time.Millisecond)
	}
	if len(scans.Items) == 0 {
		t.Fatalf("list scans = %+v", scans)
	}

	deleteReq := httptest.NewRequest(http.MethodDelete, "/api/v1/admin/library-roots/"+created.ID, nil)
	deleteReq.AddCookie(cookie)
	deleteReq.Header.Set(auth.AdminCSRFHeader, session.CSRFToken)
	deleteRes := httptest.NewRecorder()
	handler.ServeHTTP(deleteRes, deleteReq)
	if deleteRes.Code != http.StatusNoContent {
		t.Fatalf("delete root status = %d", deleteRes.Code)
	}
}

func TestAdminLibraryRootsRequireCSRF(t *testing.T) {
	database, err := db.Open(context.Background(), filepath.Join(t.TempDir(), "velin.db"))
	if err != nil {
		t.Fatalf("open database: %v", err)
	}
	t.Cleanup(func() { _ = database.Close() })

	api, err := New(Config{Version: "test", DataDir: t.TempDir()}, database)
	if err != nil {
		t.Fatalf("New() error = %v", err)
	}
	t.Cleanup(api.Stop)
	handler := api.Handler()
	setupBody, _ := json.Marshal(adminCredentialsRequest{
		Username: "admin",
		Password: strings.Repeat("x", 8),
	})
	setupReq := httptest.NewRequest(http.MethodPost, "/api/v1/admin/setup", bytes.NewReader(setupBody))
	setupRes := httptest.NewRecorder()
	handler.ServeHTTP(setupRes, setupReq)
	cookie := setupRes.Result().Cookies()[0]

	createReq := httptest.NewRequest(http.MethodPost, "/api/v1/admin/library-roots", bytes.NewReader([]byte(`{"path":"`+t.TempDir()+`"}`)))
	createReq.AddCookie(cookie)
	createRes := httptest.NewRecorder()
	handler.ServeHTTP(createRes, createReq)
	if createRes.Code != http.StatusForbidden {
		t.Fatalf("create without csrf status = %d, want %d", createRes.Code, http.StatusForbidden)
	}
}

func TestAdminLibraryRootsRejectInvalidPath(t *testing.T) {
	database, err := db.Open(context.Background(), filepath.Join(t.TempDir(), "velin.db"))
	if err != nil {
		t.Fatalf("open database: %v", err)
	}
	t.Cleanup(func() { _ = database.Close() })

	api, err := New(Config{Version: "test", DataDir: t.TempDir()}, database)
	if err != nil {
		t.Fatalf("New() error = %v", err)
	}
	t.Cleanup(api.Stop)
	handler := api.Handler()
	setupBody, _ := json.Marshal(adminCredentialsRequest{
		Username: "admin",
		Password: strings.Repeat("x", 8),
	})
	setupReq := httptest.NewRequest(http.MethodPost, "/api/v1/admin/setup", bytes.NewReader(setupBody))
	setupRes := httptest.NewRecorder()
	handler.ServeHTTP(setupRes, setupReq)
	var session adminSessionResponse
	_ = json.NewDecoder(setupRes.Body).Decode(&session)
	cookie := setupRes.Result().Cookies()[0]

	missing := filepath.Join(t.TempDir(), "missing")
	createBody, _ := json.Marshal(createLibraryRootRequest{Path: missing})
	createReq := httptest.NewRequest(http.MethodPost, "/api/v1/admin/library-roots", bytes.NewReader(createBody))
	createReq.AddCookie(cookie)
	createReq.Header.Set(auth.AdminCSRFHeader, session.CSRFToken)
	createRes := httptest.NewRecorder()
	handler.ServeHTTP(createRes, createReq)
	if createRes.Code != http.StatusBadRequest {
		t.Fatalf("invalid path status = %d", createRes.Code)
	}
	_ = os.Remove(missing)
}

func TestAdminScanDetailAndSearchDiagnostics(t *testing.T) {
	database, err := db.Open(context.Background(), filepath.Join(t.TempDir(), "velin.db"))
	if err != nil {
		t.Fatalf("open database: %v", err)
	}
	t.Cleanup(func() { _ = database.Close() })

	api, err := New(Config{Version: "test", DataDir: t.TempDir()}, database)
	if err != nil {
		t.Fatalf("New() error = %v", err)
	}
	t.Cleanup(api.Stop)
	handler := api.Handler()

	setupBody, _ := json.Marshal(adminCredentialsRequest{
		Username: "admin",
		Password: strings.Repeat("x", 8),
	})
	setupReq := httptest.NewRequest(http.MethodPost, "/api/v1/admin/setup", bytes.NewReader(setupBody))
	setupRes := httptest.NewRecorder()
	handler.ServeHTTP(setupRes, setupReq)
	cookie := setupRes.Result().Cookies()[0]

	now := time.Now().UTC().Format(time.RFC3339Nano)
	if _, err := database.Exec(`
		INSERT INTO library_roots (id, path, created_at, updated_at)
		VALUES ('root-1', ?, ?, ?)`, t.TempDir(), now, now); err != nil {
		t.Fatalf("insert root: %v", err)
	}
	if _, err := database.Exec(`
		INSERT INTO scan_runs (id, root_id, status, started_at)
		VALUES ('scan-1', 'root-1', 'failed', ?)`, now); err != nil {
		t.Fatalf("insert scan: %v", err)
	}
	if _, err := database.Exec(`
		INSERT INTO scan_errors (scan_run_id, root_id, source_name, error_code, message, created_at)
		VALUES ('scan-1', 'root-1', 'song.flac', 'metadata', 'bad tags', ?)`, now); err != nil {
		t.Fatalf("insert scan error: %v", err)
	}
	liveRoot := t.TempDir()
	if _, err := database.Exec(`
		INSERT INTO library_roots (id, path, created_at, updated_at)
		VALUES ('root-live', ?, ?, ?)`, liveRoot, now, now); err != nil {
		t.Fatalf("insert live root: %v", err)
	}
	if _, err := database.Exec(`
		INSERT INTO scan_runs (id, root_id, status, started_at, files_seen, files_indexed)
		VALUES ('scan-live', 'root-live', 'running', ?, 125000, 124900)`, now); err != nil {
		t.Fatalf("insert live scan: %v", err)
	}

	rootsPageReq := httptest.NewRequest(http.MethodGet, "/admin/roots", nil)
	rootsPageReq.AddCookie(cookie)
	rootsPageRes := httptest.NewRecorder()
	handler.ServeHTTP(rootsPageRes, rootsPageReq)
	if rootsPageRes.Code != http.StatusOK ||
		!strings.Contains(rootsPageRes.Body.String(), `data-scan-monitor="list"`) ||
		!strings.Contains(rootsPageRes.Body.String(), "125000") ||
		!strings.Contains(rootsPageRes.Body.String(), "data-local-time") ||
		!strings.Contains(rootsPageRes.Body.String(), "static/velin-icon.svg") {
		t.Fatalf("live scan admin page = %d %q", rootsPageRes.Code, rootsPageRes.Body.String())
	}

	scanReq := httptest.NewRequest(http.MethodGet, "/api/v1/admin/scans/scan-1", nil)
	scanReq.AddCookie(cookie)
	scanRes := httptest.NewRecorder()
	handler.ServeHTTP(scanRes, scanReq)
	var scan scanRunResponse
	if scanRes.Code != http.StatusOK || json.NewDecoder(scanRes.Body).Decode(&scan) != nil || scan.Status != "failed" {
		t.Fatalf("get scan = %d %+v", scanRes.Code, scan)
	}

	scanPageReq := httptest.NewRequest(http.MethodGet, "/admin/scans/scan-1", nil)
	scanPageReq.AddCookie(cookie)
	scanPageRes := httptest.NewRecorder()
	handler.ServeHTTP(scanPageRes, scanPageReq)
	if scanPageRes.Code != http.StatusOK ||
		!strings.Contains(scanPageRes.Body.String(), `href="../static/admin.css"`) ||
		!strings.Contains(scanPageRes.Body.String(), `href="../roots"`) {
		t.Fatalf("scan detail admin page = %d %q", scanPageRes.Code, scanPageRes.Body.String())
	}

	errorsReq := httptest.NewRequest(http.MethodGet, "/api/v1/admin/scans/scan-1/errors", nil)
	errorsReq.AddCookie(cookie)
	errorsRes := httptest.NewRecorder()
	handler.ServeHTTP(errorsRes, errorsReq)
	var errorsBody scanErrorListResponse
	if errorsRes.Code != http.StatusOK || json.NewDecoder(errorsRes.Body).Decode(&errorsBody) != nil || errorsBody.Total != 1 || len(errorsBody.Items) != 1 {
		t.Fatalf("list scan errors = %d %+v", errorsRes.Code, errorsBody)
	}

	searchReq := httptest.NewRequest(http.MethodGet, "/api/v1/admin/search-diagnostics?q=needle", nil)
	searchReq.AddCookie(cookie)
	searchRes := httptest.NewRecorder()
	handler.ServeHTTP(searchRes, searchReq)
	var search searchDiagnosticsResultResponse
	if searchRes.Code != http.StatusOK || json.NewDecoder(searchRes.Body).Decode(&search) != nil || !search.Valid || search.MatchQuery == "" {
		t.Fatalf("search diagnostics = %d %+v", searchRes.Code, search)
	}
}
