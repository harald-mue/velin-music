package admin_test

import (
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"path/filepath"
	"strings"
	"testing"

	"github.com/harald-mue/velin-music/server/internal/admin"
	"github.com/harald-mue/velin-music/server/internal/auth"
	"github.com/harald-mue/velin-music/server/internal/db"
	"github.com/harald-mue/velin-music/server/internal/library"
)

func openAdminTestHandler(t *testing.T) http.Handler {
	t.Helper()
	database, err := db.Open(context.Background(), filepath.Join(t.TempDir(), "velin.db"))
	if err != nil {
		t.Fatalf("open database: %v", err)
	}
	t.Cleanup(func() { _ = database.Close() })

	mux := http.NewServeMux()
	roots := library.NewStore(database)
	cache, err := library.NewArtworkCache(t.TempDir())
	if err != nil {
		t.Fatalf("NewArtworkCache() error = %v", err)
	}
	covers := library.NewCoverStore(database, cache)
	tracks := library.NewTrackRepositoryWithArtwork(database, covers)
	scanner := library.NewScanner(roots, tracks)
	scanQueries := library.NewScanQueryRepository(database)
	scans := library.NewScanService(scanner, roots, scanQueries)
	queries := library.NewQueryRepository(database)
	handler, err := admin.New(admin.Config{
		Version:   "test",
		PublicURL: "http://velin.local:8080",
	}, auth.NewAdminRepository(database), auth.NewTokenRepository(database), auth.NewPairingRepository(database), auth.NewRateLimiter(100, auth.AdminLoginWindow), roots, scans, scanQueries, queries)
	if err != nil {
		t.Fatalf("New() error = %v", err)
	}
	handler.Register(mux)
	return mux
}

func TestAdminSetupLoginAndPairingFlow(t *testing.T) {
	server := httptest.NewServer(openAdminTestHandler(t))
	t.Cleanup(server.Close)
	client := &http.Client{
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			return http.ErrUseLastResponse
		},
	}

	setupPage, err := client.Get(server.URL + "/admin/setup")
	if err != nil {
		t.Fatalf("GET setup: %v", err)
	}
	setupBody, _ := io.ReadAll(setupPage.Body)
	_ = setupPage.Body.Close()
	if setupPage.StatusCode != http.StatusOK || !strings.Contains(string(setupBody), "Set up Velin") || !strings.Contains(string(setupBody), `href="static/admin.css"`) {
		t.Fatalf("setup page = %d %q", setupPage.StatusCode, setupBody)
	}

	setupForm := url.Values{}
	setupForm.Set("username", "admin")
	setupForm.Set("password", strings.Repeat("x", 8))
	setupRes, err := client.PostForm(server.URL+"/admin/setup", setupForm)
	if err != nil {
		t.Fatalf("POST setup: %v", err)
	}
	_ = setupRes.Body.Close()
	if setupRes.StatusCode != http.StatusSeeOther {
		t.Fatalf("setup status = %d, want %d", setupRes.StatusCode, http.StatusSeeOther)
	}
	cookie := setupRes.Cookies()[0]

	devicesReq, err := http.NewRequest(http.MethodGet, server.URL+"/admin/devices", nil)
	if err != nil {
		t.Fatalf("devices request: %v", err)
	}
	devicesReq.AddCookie(cookie)
	devicesRes, err := client.Do(devicesReq)
	if err != nil {
		t.Fatalf("GET devices: %v", err)
	}
	devicesBody, _ := io.ReadAll(devicesRes.Body)
	_ = devicesRes.Body.Close()
	if devicesRes.StatusCode != http.StatusOK || !strings.Contains(string(devicesBody), "No devices have been paired yet") {
		t.Fatalf("devices page = %d %q", devicesRes.StatusCode, devicesBody)
	}

	pairingReq, err := http.NewRequest(http.MethodGet, server.URL+"/admin/pairing", nil)
	if err != nil {
		t.Fatalf("pairing request: %v", err)
	}
	pairingReq.AddCookie(cookie)
	pairingRes, err := client.Do(pairingReq)
	if err != nil {
		t.Fatalf("GET pairing: %v", err)
	}
	pairingBody, _ := io.ReadAll(pairingRes.Body)
	_ = pairingRes.Body.Close()
	if pairingRes.StatusCode != http.StatusOK || !strings.Contains(string(pairingBody), `name="csrf_token"`) || !strings.Contains(string(pairingBody), "data-browser-origin-default") {
		t.Fatalf("pairing page = %d", pairingRes.StatusCode)
	}
	csrf := extractInputValue(string(pairingBody), "csrf_token")
	if csrf == "" {
		t.Fatal("csrf token missing from pairing page")
	}

	pairForm := url.Values{}
	pairForm.Set("csrf_token", csrf)
	pairForm.Set("device_name", "Pixel")
	pairForm.Set("server_url", "http://velin.local:8080")
	pairPost, err := http.NewRequest(http.MethodPost, server.URL+"/admin/pairing", strings.NewReader(pairForm.Encode()))
	if err != nil {
		t.Fatalf("pair post request: %v", err)
	}
	pairPost.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	pairPost.AddCookie(cookie)
	pairPostRes, err := client.Do(pairPost)
	if err != nil {
		t.Fatalf("POST pairing: %v", err)
	}
	resultBody, _ := io.ReadAll(pairPostRes.Body)
	_ = pairPostRes.Body.Close()
	if pairPostRes.StatusCode != http.StatusOK || !strings.Contains(string(resultBody), "Pairing code created") || !strings.Contains(string(resultBody), "data-local-time") {
		t.Fatalf("pairing result = %d %q", pairPostRes.StatusCode, resultBody)
	}
	if !strings.Contains(string(resultBody), "http://velin.local:8080") || !strings.Contains(string(resultBody), "server_url") {
		t.Fatalf("qr payload missing from result: %q", resultBody)
	}
	if !strings.Contains(string(resultBody), `src="data:image/png;base64,`) || !strings.Contains(string(resultBody), "Velin pairing QR code") {
		t.Fatalf("qr image missing from result")
	}
}

func TestAdminRootsPageAddAndScan(t *testing.T) {
	server := httptest.NewServer(openAdminTestHandler(t))
	t.Cleanup(server.Close)
	client := &http.Client{
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			return http.ErrUseLastResponse
		},
	}

	setupForm := url.Values{}
	setupForm.Set("username", "admin")
	setupForm.Set("password", strings.Repeat("x", 8))
	setupRes, err := client.PostForm(server.URL+"/admin/setup", setupForm)
	if err != nil {
		t.Fatalf("POST setup: %v", err)
	}
	_ = setupRes.Body.Close()
	cookie := setupRes.Cookies()[0]

	rootsReq, err := http.NewRequest(http.MethodGet, server.URL+"/admin/roots", nil)
	if err != nil {
		t.Fatalf("roots request: %v", err)
	}
	rootsReq.AddCookie(cookie)
	rootsRes, err := client.Do(rootsReq)
	if err != nil {
		t.Fatalf("GET roots: %v", err)
	}
	rootsBody, _ := io.ReadAll(rootsRes.Body)
	_ = rootsRes.Body.Close()
	if rootsRes.StatusCode != http.StatusOK || !strings.Contains(string(rootsBody), "Library roots") {
		t.Fatalf("roots page = %d %q", rootsRes.StatusCode, rootsBody)
	}
	csrf := extractInputValue(string(rootsBody), "csrf_token")
	if csrf == "" {
		t.Fatal("csrf token missing from roots page")
	}

	rootDir := t.TempDir()
	addForm := url.Values{}
	addForm.Set("csrf_token", csrf)
	addForm.Set("path", rootDir)
	addReq, err := http.NewRequest(http.MethodPost, server.URL+"/admin/roots", strings.NewReader(addForm.Encode()))
	if err != nil {
		t.Fatalf("add root request: %v", err)
	}
	addReq.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	addReq.AddCookie(cookie)
	addRes, err := client.Do(addReq)
	if err != nil {
		t.Fatalf("POST roots: %v", err)
	}
	_ = addRes.Body.Close()
	if addRes.StatusCode != http.StatusSeeOther || addRes.Header.Get("Location") != "roots" {
		t.Fatalf("add root status = %d location = %q", addRes.StatusCode, addRes.Header.Get("Location"))
	}

	rootsReq2, err := http.NewRequest(http.MethodGet, server.URL+"/admin/roots", nil)
	if err != nil {
		t.Fatalf("roots request 2: %v", err)
	}
	rootsReq2.AddCookie(cookie)
	rootsRes2, err := client.Do(rootsReq2)
	if err != nil {
		t.Fatalf("GET roots 2: %v", err)
	}
	rootsBody2, _ := io.ReadAll(rootsRes2.Body)
	_ = rootsRes2.Body.Close()
	if rootsRes2.StatusCode != http.StatusOK || !strings.Contains(string(rootsBody2), rootDir) {
		t.Fatalf("roots page after add = %d %q", rootsRes2.StatusCode, rootsBody2)
	}
}

func TestAdminScanDetailAndSearchPages(t *testing.T) {
	server := httptest.NewServer(openAdminTestHandler(t))
	t.Cleanup(server.Close)
	client := &http.Client{
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			return http.ErrUseLastResponse
		},
	}

	setupForm := url.Values{}
	setupForm.Set("username", "admin")
	setupForm.Set("password", strings.Repeat("x", 8))
	setupRes, err := client.PostForm(server.URL+"/admin/setup", setupForm)
	if err != nil {
		t.Fatalf("POST setup: %v", err)
	}
	_ = setupRes.Body.Close()
	cookie := setupRes.Cookies()[0]

	searchReq, err := http.NewRequest(http.MethodGet, server.URL+"/admin/search?q=needle", nil)
	if err != nil {
		t.Fatalf("search request: %v", err)
	}
	searchReq.AddCookie(cookie)
	searchRes, err := client.Do(searchReq)
	if err != nil {
		t.Fatalf("GET search: %v", err)
	}
	searchBody, _ := io.ReadAll(searchRes.Body)
	_ = searchRes.Body.Close()
	if searchRes.StatusCode != http.StatusOK || !strings.Contains(string(searchBody), "Search diagnostics") || !strings.Contains(string(searchBody), "FTS match query") {
		t.Fatalf("search page = %d %q", searchRes.StatusCode, searchBody)
	}
}

func TestAdminBrandAssetsAreEmbedded(t *testing.T) {
	handler := openAdminTestHandler(t)
	for _, test := range []struct {
		path        string
		contentType string
		contains    string
	}{
		{path: "/admin/static/velin-icon.svg", contentType: "image/svg+xml", contains: "M34 34h9.5"},
		{path: "/admin/static/admin.js", contentType: "text/javascript", contains: "Intl.RelativeTimeFormat"},
		{path: "/admin/static/admin.js", contentType: "text/javascript", contains: "lastIndexOf(adminMarker)"},
		{path: "/admin/static/admin.js", contentType: "text/javascript", contains: `current.username = ""`},
		{path: "/admin/static/admin.css", contentType: "text/css", contains: ".device-status.status-completed"},
	} {
		request := httptest.NewRequest(http.MethodGet, test.path, nil)
		response := httptest.NewRecorder()
		handler.ServeHTTP(response, request)
		if response.Code != http.StatusOK {
			t.Fatalf("GET %s status = %d", test.path, response.Code)
		}
		if !strings.Contains(response.Header().Get("Content-Type"), test.contentType) {
			t.Fatalf("GET %s content type = %q", test.path, response.Header().Get("Content-Type"))
		}
		if !strings.Contains(response.Body.String(), test.contains) {
			t.Fatalf("GET %s body does not contain %q", test.path, test.contains)
		}
		if response.Header().Get("Cache-Control") != "no-store" ||
			response.Header().Get("X-Content-Type-Options") != "nosniff" ||
			response.Header().Get("X-Frame-Options") != "DENY" ||
			!strings.Contains(response.Header().Get("Content-Security-Policy"), "frame-ancestors 'none'") {
			t.Fatalf("GET %s security headers = %v", test.path, response.Header())
		}
	}
}

func TestAdminRootRedirectIsPrefixRelative(t *testing.T) {
	handler := openAdminTestHandler(t)
	request := httptest.NewRequest(http.MethodGet, "/admin", nil)
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, request)
	if response.Code != http.StatusMovedPermanently || response.Header().Get("Location") != "admin/" {
		t.Fatalf("admin root redirect = %d %q", response.Code, response.Header().Get("Location"))
	}
}

func TestAdminProtectedPagesRedirectToLogin(t *testing.T) {
	server := httptest.NewServer(openAdminTestHandler(t))
	t.Cleanup(server.Close)
	client := &http.Client{
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			return http.ErrUseLastResponse
		},
	}
	res, err := client.Get(server.URL + "/admin/devices")
	if err != nil {
		t.Fatalf("GET devices: %v", err)
	}
	_ = res.Body.Close()
	if res.StatusCode != http.StatusSeeOther || res.Header.Get("Location") != "login" {
		t.Fatalf("status = %d location = %q", res.StatusCode, res.Header.Get("Location"))
	}
}

func extractInputValue(html, name string) string {
	marker := `name="` + name + `" value="`
	start := strings.Index(html, marker)
	if start < 0 {
		return ""
	}
	start += len(marker)
	end := strings.Index(html[start:], `"`)
	if end < 0 {
		return ""
	}
	return html[start : start+end]
}
