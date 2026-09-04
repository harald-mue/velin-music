package auth

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestAdminRepositorySetupLoginAndLogout(t *testing.T) {
	database := openAuthTestDB(t)
	repository := NewAdminRepository(database)
	ctx := context.Background()

	needsSetup, err := repository.NeedsSetup(ctx)
	if err != nil || !needsSetup {
		t.Fatalf("NeedsSetup() = %v, %v; want true, nil", needsSetup, err)
	}

	session, err := repository.Setup(ctx, "admin", strings.Repeat("x", minAdminPasswordBytes))
	if err != nil {
		t.Fatalf("Setup() error = %v", err)
	}
	if session.Username != "admin" || session.Token == "" || session.CSRFToken == "" {
		t.Fatalf("session = %+v", session)
	}

	needsSetup, err = repository.NeedsSetup(ctx)
	if err != nil || needsSetup {
		t.Fatalf("NeedsSetup() after setup = %v, %v; want false, nil", needsSetup, err)
	}
	if _, err := repository.Setup(ctx, "other", strings.Repeat("y", minAdminPasswordBytes)); !errors.Is(err, ErrSetupComplete) {
		t.Fatalf("Setup(again) error = %v, want ErrSetupComplete", err)
	}

	loaded, err := repository.AuthenticateSession(ctx, session.Token)
	if err != nil {
		t.Fatalf("AuthenticateSession() error = %v", err)
	}
	if loaded.Username != "admin" {
		t.Fatalf("loaded session = %+v", loaded)
	}

	if err := repository.Logout(ctx, session.Token); err != nil {
		t.Fatalf("Logout() error = %v", err)
	}
	if _, err := repository.AuthenticateSession(ctx, session.Token); !errors.Is(err, ErrInvalidSession) {
		t.Fatalf("AuthenticateSession(revoked) error = %v, want ErrInvalidSession", err)
	}

	loggedIn, err := repository.Login(ctx, "admin", strings.Repeat("x", minAdminPasswordBytes))
	if err != nil {
		t.Fatalf("Login() error = %v", err)
	}
	if loggedIn.Username != "admin" {
		t.Fatalf("login session = %+v", loggedIn)
	}
}

func TestAdminRepositoryRejectsInvalidCredentials(t *testing.T) {
	repository := NewAdminRepository(openAuthTestDB(t))
	ctx := context.Background()

	if _, err := repository.Login(ctx, "missing", strings.Repeat("x", minAdminPasswordBytes)); !errors.Is(err, ErrSetupRequired) {
		t.Fatalf("Login(before setup) error = %v, want ErrSetupRequired", err)
	}
	if _, err := repository.Setup(ctx, "admin", "short"); !errors.Is(err, ErrInvalidPassword) {
		t.Fatalf("Setup(short password) error = %v, want ErrInvalidPassword", err)
	}
	if _, err := repository.Setup(ctx, "", strings.Repeat("x", minAdminPasswordBytes)); !errors.Is(err, ErrInvalidUsername) {
		t.Fatalf("Setup(empty username) error = %v, want ErrInvalidUsername", err)
	}

	if _, err := repository.Setup(ctx, "admin", strings.Repeat("x", minAdminPasswordBytes)); err != nil {
		t.Fatalf("Setup() error = %v", err)
	}
	if _, err := repository.Login(ctx, "admin", strings.Repeat("y", minAdminPasswordBytes)); !errors.Is(err, ErrInvalidCredentials) {
		t.Fatalf("Login(wrong password) error = %v, want ErrInvalidCredentials", err)
	}
}

func TestAdminMiddlewareRequiresSessionAndCSRF(t *testing.T) {
	database := openAuthTestDB(t)
	repository := NewAdminRepository(database)
	ctx := context.Background()

	session, err := repository.Setup(ctx, "admin", strings.Repeat("x", minAdminPasswordBytes))
	if err != nil {
		t.Fatalf("Setup() error = %v", err)
	}

	protected := RequireAdminSession(repository)(RequireAdminCSRF(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNoContent)
	})))

	t.Run("missing session", func(t *testing.T) {
		req := httptest.NewRequest(http.MethodPost, "/admin", nil)
		res := httptest.NewRecorder()
		protected.ServeHTTP(res, req)
		if res.Code != http.StatusUnauthorized {
			t.Fatalf("status = %d, want %d", res.Code, http.StatusUnauthorized)
		}
	})

	t.Run("missing csrf", func(t *testing.T) {
		req := httptest.NewRequest(http.MethodPost, "/admin", nil)
		req.AddCookie(&http.Cookie{Name: AdminSessionCookie, Value: session.Token})
		res := httptest.NewRecorder()
		protected.ServeHTTP(res, req)
		if res.Code != http.StatusForbidden {
			t.Fatalf("status = %d, want %d", res.Code, http.StatusForbidden)
		}
	})

	t.Run("valid session and csrf", func(t *testing.T) {
		req := httptest.NewRequest(http.MethodPost, "/admin", nil)
		req.AddCookie(&http.Cookie{Name: AdminSessionCookie, Value: session.Token})
		req.Header.Set(AdminCSRFHeader, session.CSRFToken)
		res := httptest.NewRecorder()
		protected.ServeHTTP(res, req)
		if res.Code != http.StatusNoContent {
			t.Fatalf("status = %d, want %d", res.Code, http.StatusNoContent)
		}
	})
}

func TestNormalizeServerURL(t *testing.T) {
	for _, value := range []string{"", "ftp://example.com", "http://user@host", "http://host/path@"} {
		if _, err := NormalizeServerURL(value); err == nil {
			t.Fatalf("NormalizeServerURL(%q) error = nil, want error", value)
		}
	}
	got, err := NormalizeServerURL("https://velin.local:8080/")
	if err != nil || got != "https://velin.local:8080" {
		t.Fatalf("NormalizeServerURL() = %q, %v", got, err)
	}
}
