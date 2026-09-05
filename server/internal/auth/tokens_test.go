package auth

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/harald-mue/velin-music/server/internal/db"
)

func openAuthTestDB(t *testing.T) *sql.DB {
	t.Helper()
	database, err := db.Open(context.Background(), filepath.Join(t.TempDir(), "velin.db"))
	if err != nil {
		t.Fatalf("open auth test database: %v", err)
	}
	t.Cleanup(func() { _ = database.Close() })
	return database
}

func TestTokenRepositoryIssuesVerifiesAndRevokes(t *testing.T) {
	database := openAuthTestDB(t)
	repository := NewTokenRepository(database)
	ctx := context.Background()

	issued, err := repository.Issue(ctx, "Living Room")
	if err != nil {
		t.Fatalf("Issue() error = %v", err)
	}
	if issued.DeviceID == "" || !strings.Contains(issued.Token, ".") {
		t.Fatalf("issued token = %+v", issued)
	}

	deviceID, err := repository.Authenticate(ctx, issued.Token)
	if err != nil {
		t.Fatalf("Authenticate() error = %v", err)
	}
	if deviceID != issued.DeviceID {
		t.Fatalf("device ID = %q, want %q", deviceID, issued.DeviceID)
	}

	devices, err := repository.ListDevices(ctx)
	if err != nil {
		t.Fatalf("ListDevices() error = %v", err)
	}
	if len(devices) != 1 || devices[0].Name != "Living Room" || devices[0].LastUsedAt == nil || devices[0].RevokedAt != nil {
		t.Fatalf("devices = %+v", devices)
	}

	if err := repository.Revoke(ctx, issued.DeviceID); err != nil {
		t.Fatalf("Revoke() error = %v", err)
	}
	if _, err := repository.Authenticate(ctx, issued.Token); !errors.Is(err, ErrInvalidToken) {
		t.Fatalf("Authenticate(revoked) error = %v, want ErrInvalidToken", err)
	}
	if err := repository.Revoke(ctx, issued.DeviceID); !errors.Is(err, ErrDeviceNotFound) {
		t.Fatalf("Revoke(again) error = %v, want ErrDeviceNotFound", err)
	}
}

func TestTokenRepositoryRejectsMalformedOrWrongTokens(t *testing.T) {
	repository := NewTokenRepository(openAuthTestDB(t))
	ctx := context.Background()

	for _, token := range []string{"", "missing-dot", "not-hex.secret", "0123456789abcdef0123456789abcdef.wrong"} {
		if _, err := repository.Authenticate(ctx, token); !errors.Is(err, ErrInvalidToken) {
			t.Fatalf("Authenticate(%q) error = %v, want ErrInvalidToken", token, err)
		}
	}
	if _, err := repository.Issue(ctx, " "); !errors.Is(err, ErrInvalidDeviceName) {
		t.Fatalf("Issue(empty name) error = %v, want ErrInvalidDeviceName", err)
	}
}

func TestPairingRepositoryCreatesAndExchangesOnce(t *testing.T) {
	database := openAuthTestDB(t)
	pairing := NewPairingRepository(database)
	tokens := NewTokenRepository(database)
	ctx := context.Background()

	created, err := pairing.Create(ctx, "Pixel")
	if err != nil {
		t.Fatalf("Create() error = %v", err)
	}
	if created.Code == "" || created.DeviceName != "Pixel" {
		t.Fatalf("created pairing = %+v", created)
	}
	if created.ExpiresAt.Before(time.Now().Add(pairingValidity - time.Minute)) {
		t.Fatalf("expires_at = %v, want about %v from now", created.ExpiresAt, pairingValidity)
	}

	issued, err := pairing.Exchange(ctx, created.Code, tokens)
	if err != nil {
		t.Fatalf("Exchange() error = %v", err)
	}
	if issued.DeviceID == "" || issued.Token == "" {
		t.Fatalf("issued token = %+v", issued)
	}
	if _, err := pairing.Exchange(ctx, created.Code, tokens); !errors.Is(err, ErrInvalidPairingCode) {
		t.Fatalf("Exchange(reused) error = %v, want ErrInvalidPairingCode", err)
	}
	if _, err := tokens.Authenticate(ctx, issued.Token); err != nil {
		t.Fatalf("Authenticate(exchanged) error = %v", err)
	}
}

func TestPairingRepositoryRejectsInvalidCodes(t *testing.T) {
	pairing := NewPairingRepository(openAuthTestDB(t))
	tokens := NewTokenRepository(openAuthTestDB(t))
	ctx := context.Background()

	if _, err := pairing.Exchange(ctx, "", tokens); !errors.Is(err, ErrInvalidPairingCode) {
		t.Fatalf("Exchange(empty) error = %v, want ErrInvalidPairingCode", err)
	}
	if _, err := pairing.Exchange(ctx, "missing", tokens); !errors.Is(err, ErrInvalidPairingCode) {
		t.Fatalf("Exchange(missing) error = %v, want ErrInvalidPairingCode", err)
	}
	if _, err := pairing.Create(ctx, strings.Repeat("x", maxDeviceNameRunes+1)); !errors.Is(err, ErrInvalidDeviceName) {
		t.Fatalf("Create(long name) error = %v, want ErrInvalidDeviceName", err)
	}
}

func TestRequireDeviceTokenMiddleware(t *testing.T) {
	database := openAuthTestDB(t)
	tokens := NewTokenRepository(database)
	ctx := context.Background()

	issued, err := tokens.Issue(ctx, "Phone")
	if err != nil {
		t.Fatalf("Issue() error = %v", err)
	}

	protected := RequireDeviceToken(tokens)(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		deviceID, ok := DeviceIDFromContext(r.Context())
		if !ok || deviceID == "" {
			t.Fatal("device ID missing from context")
		}
		w.WriteHeader(http.StatusNoContent)
	}))

	t.Run("missing token", func(t *testing.T) {
		req := httptest.NewRequest(http.MethodGet, "/protected", nil)
		res := httptest.NewRecorder()
		protected.ServeHTTP(res, req)
		if res.Code != http.StatusUnauthorized {
			t.Fatalf("status = %d, want %d", res.Code, http.StatusUnauthorized)
		}
	})

	t.Run("valid token", func(t *testing.T) {
		req := httptest.NewRequest(http.MethodGet, "/protected", nil)
		req.Header.Set("Authorization", "Bearer "+issued.Token)
		res := httptest.NewRecorder()
		protected.ServeHTTP(res, req)
		if res.Code != http.StatusNoContent {
			t.Fatalf("status = %d, want %d", res.Code, http.StatusNoContent)
		}
	})
}

func TestRateLimiterBlocksRepeatedHits(t *testing.T) {
	limiter := NewRateLimiter(2, time.Minute)
	if !limiter.Allow("client") || !limiter.Allow("client") || limiter.Allow("client") {
		t.Fatal("rate limiter did not block third request")
	}
	if !limiter.Allow("other") {
		t.Fatal("rate limiter blocked unrelated client")
	}
}

func TestRateLimiterUsesRemoteIPAndIgnoresForwardedHeaders(t *testing.T) {
	limiter := NewRateLimiter(2, time.Minute)
	handler := limiter.Limit(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusNoContent)
	}))
	for attempt := 0; attempt < 3; attempt++ {
		request := httptest.NewRequest(http.MethodPost, "/login", nil)
		request.RemoteAddr = fmt.Sprintf("192.0.2.10:%d", 10000+attempt)
		request.Header.Set("X-Forwarded-For", fmt.Sprintf("198.51.100.%d", attempt+1))
		response := httptest.NewRecorder()
		handler.ServeHTTP(response, request)
		want := http.StatusNoContent
		if attempt == 2 {
			want = http.StatusTooManyRequests
		}
		if response.Code != want {
			t.Fatalf("attempt %d status = %d, want %d", attempt+1, response.Code, want)
		}
	}
}

func TestRateLimiterBoundsDistinctClientKeys(t *testing.T) {
	limiter := NewRateLimiter(1, time.Hour)
	for index := 0; index < maxRateLimitKeys; index++ {
		if !limiter.Allow(fmt.Sprintf("client-%d", index)) {
			t.Fatalf("client %d was unexpectedly rejected", index)
		}
	}
	if limiter.Allow("overflow-client") {
		t.Fatal("rate limiter accepted a key beyond its bounded capacity")
	}
}
