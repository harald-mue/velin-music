package httpapi

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"

	"github.com/harald-mue/velin-music/server/internal/auth"
	"github.com/harald-mue/velin-music/server/internal/db"
)

func TestAdminSetupLoginDevicesAndPairing(t *testing.T) {
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

	statusReq := httptest.NewRequest(http.MethodGet, "/api/v1/admin/setup-status", nil)
	statusRes := httptest.NewRecorder()
	handler.ServeHTTP(statusRes, statusReq)
	var setupStatus setupStatusResponse
	if statusRes.Code != http.StatusOK || json.NewDecoder(statusRes.Body).Decode(&setupStatus) != nil || !setupStatus.NeedsSetup {
		t.Fatalf("setup status = %d %+v", statusRes.Code, setupStatus)
	}

	setupBody, _ := json.Marshal(adminCredentialsRequest{
		Username: "admin",
		Password: strings.Repeat("x", 12),
	})
	setupReq := httptest.NewRequest(http.MethodPost, "/api/v1/admin/setup", bytes.NewReader(setupBody))
	setupRes := httptest.NewRecorder()
	handler.ServeHTTP(setupRes, setupReq)
	var session adminSessionResponse
	if setupRes.Code != http.StatusCreated || json.NewDecoder(setupRes.Body).Decode(&session) != nil || session.CSRFToken == "" {
		t.Fatalf("setup response = %d %+v", setupRes.Code, session)
	}
	setupCookie := setupRes.Result().Cookies()[0]

	pairBody, _ := json.Marshal(createPairingRequest{DeviceName: "Pixel"})
	pairReq := httptest.NewRequest(http.MethodPost, "/api/v1/admin/pairing-codes", bytes.NewReader(pairBody))
	pairReq.AddCookie(setupCookie)
	pairReq.Header.Set(auth.AdminCSRFHeader, session.CSRFToken)
	pairRes := httptest.NewRecorder()
	handler.ServeHTTP(pairRes, pairReq)
	var pairing createPairingResponse
	if pairRes.Code != http.StatusCreated || json.NewDecoder(pairRes.Body).Decode(&pairing) != nil {
		t.Fatalf("pairing response = %d %s", pairRes.Code, pairRes.Body.String())
	}
	if pairing.QRPayload.ServerURL != "http://velin.local:8080" || pairing.QRPayload.Code != pairing.Code {
		t.Fatalf("pairing payload = %+v", pairing)
	}

	devicesReq := httptest.NewRequest(http.MethodGet, "/api/v1/admin/devices", nil)
	devicesReq.AddCookie(setupCookie)
	devicesRes := httptest.NewRecorder()
	handler.ServeHTTP(devicesRes, devicesReq)
	if devicesRes.Code != http.StatusOK {
		t.Fatalf("devices status = %d", devicesRes.Code)
	}

	logoutReq := httptest.NewRequest(http.MethodPost, "/api/v1/admin/logout", nil)
	logoutReq.AddCookie(setupCookie)
	logoutReq.Header.Set(auth.AdminCSRFHeader, session.CSRFToken)
	logoutRes := httptest.NewRecorder()
	handler.ServeHTTP(logoutRes, logoutReq)
	if logoutRes.Code != http.StatusNoContent {
		t.Fatalf("logout status = %d", logoutRes.Code)
	}
}

func TestAdminMutationsRejectMissingCSRF(t *testing.T) {
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
		Password: strings.Repeat("x", 12),
	})
	setupReq := httptest.NewRequest(http.MethodPost, "/api/v1/admin/setup", bytes.NewReader(setupBody))
	setupRes := httptest.NewRecorder()
	handler.ServeHTTP(setupRes, setupReq)
	cookie := setupRes.Result().Cookies()[0]

	pairReq := httptest.NewRequest(http.MethodPost, "/api/v1/admin/pairing-codes", bytes.NewReader([]byte(`{"device_name":"Pixel","server_url":"http://velin.local:8080"}`)))
	pairReq.AddCookie(cookie)
	pairRes := httptest.NewRecorder()
	handler.ServeHTTP(pairRes, pairReq)
	if pairRes.Code != http.StatusForbidden {
		t.Fatalf("pairing without csrf status = %d, want %d", pairRes.Code, http.StatusForbidden)
	}
}
