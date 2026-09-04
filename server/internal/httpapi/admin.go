package httpapi

import (
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strings"
	"time"

	"github.com/harald-mue/velin-music/server/internal/auth"
	"github.com/harald-mue/velin-music/server/internal/library"
)

const maxAdminRequestBytes = 4096

type adminServices struct {
	version       string
	publicURL     string
	secureCookies bool
	admins        *auth.AdminRepository
	tokens        *auth.TokenRepository
	pairing       *auth.PairingRepository
	loginLimiter  *auth.RateLimiter
	roots         *library.Store
	scans         *library.ScanService
	scanQueries   *library.ScanQueryRepository
	queries       *library.QueryRepository
}

type setupStatusResponse struct {
	NeedsSetup bool `json:"needs_setup"`
}

type adminCredentialsRequest struct {
	Username string `json:"username"`
	Password string `json:"password"`
}

type adminSessionResponse struct {
	Username  string `json:"username"`
	CSRFToken string `json:"csrf_token"`
	ExpiresAt string `json:"expires_at"`
}

type deviceResponse struct {
	ID         string  `json:"id"`
	Name       string  `json:"name"`
	CreatedAt  string  `json:"created_at"`
	LastUsedAt *string `json:"last_used_at,omitempty"`
	RevokedAt  *string `json:"revoked_at,omitempty"`
}

type deviceListResponse struct {
	Items []deviceResponse `json:"items"`
}

type createPairingRequest struct {
	DeviceName string `json:"device_name"`
	ServerURL  string `json:"server_url,omitempty"`
}

type qrPayload struct {
	ServerURL string `json:"server_url"`
	Code      string `json:"code"`
}

type createPairingResponse struct {
	ID         string    `json:"id"`
	DeviceName string    `json:"device_name"`
	Code       string    `json:"code"`
	ExpiresAt  string    `json:"expires_at"`
	QRPayload  qrPayload `json:"qr_payload"`
}

func registerAdminRoutes(mux *http.ServeMux, services adminServices) {
	adminAuth := auth.RequireAdminSession(services.admins)
	adminMutate := func(handler http.HandlerFunc) http.Handler {
		return adminAuth(auth.RequireAdminCSRF(http.HandlerFunc(handler)))
	}

	mux.HandleFunc("GET /api/v1/admin/setup-status", services.setupStatusHandler())
	mux.Handle("POST /api/v1/admin/setup", services.loginLimiter.Limit(http.HandlerFunc(services.setupHandler())))
	mux.Handle("POST /api/v1/admin/login", services.loginLimiter.Limit(http.HandlerFunc(services.loginHandler())))
	mux.Handle("POST /api/v1/admin/logout", adminMutate(services.logoutHandler()))
	mux.Handle("GET /api/v1/admin/me", adminAuth(http.HandlerFunc(services.meHandler())))
	mux.Handle("GET /api/v1/admin/devices", adminAuth(http.HandlerFunc(services.listDevicesHandler())))
	mux.Handle("DELETE /api/v1/admin/devices/{id}", adminMutate(services.revokeDeviceHandler()))
	mux.Handle("POST /api/v1/admin/pairing-codes", adminMutate(services.createPairingHandler()))
	registerAdminLibraryRoutes(mux, adminAuth, adminMutate, services.roots, services.scans, services.scanQueries, services.queries)
}

func (s adminServices) setupStatusHandler() http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		needsSetup, err := s.admins.NeedsSetup(r.Context())
		if err != nil {
			writeJSONError(w, http.StatusInternalServerError, "internal_error", "internal error")
			return
		}
		writeJSON(w, http.StatusOK, setupStatusResponse{NeedsSetup: needsSetup})
	}
}

func (s adminServices) setupHandler() http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		request, ok := decodeAdminCredentials(w, r)
		if !ok {
			return
		}
		session, err := s.admins.Setup(r.Context(), request.Username, request.Password)
		if errors.Is(err, auth.ErrSetupComplete) {
			writeJSONError(w, http.StatusConflict, "setup_complete", "admin setup already complete")
			return
		}
		if errors.Is(err, auth.ErrInvalidUsername) || errors.Is(err, auth.ErrInvalidPassword) {
			writeJSONError(w, http.StatusBadRequest, "invalid_request", "invalid request")
			return
		}
		if err != nil {
			writeJSONError(w, http.StatusInternalServerError, "internal_error", "internal error")
			return
		}
		secure := auth.RequestSecureCookies(r, s.secureCookies)
		auth.SetSessionCookie(w, session, secure)
		writeJSON(w, http.StatusCreated, adminSessionResponseFrom(session))
	}
}

func (s adminServices) loginHandler() http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		request, ok := decodeAdminCredentials(w, r)
		if !ok {
			return
		}
		session, err := s.admins.Login(r.Context(), request.Username, request.Password)
		if errors.Is(err, auth.ErrSetupRequired) {
			writeJSONError(w, http.StatusConflict, "setup_required", "admin setup required")
			return
		}
		if errors.Is(err, auth.ErrInvalidCredentials) {
			writeJSONError(w, http.StatusUnauthorized, "invalid_credentials", "invalid credentials")
			return
		}
		if err != nil {
			writeJSONError(w, http.StatusInternalServerError, "internal_error", "internal error")
			return
		}
		secure := auth.RequestSecureCookies(r, s.secureCookies)
		auth.SetSessionCookie(w, session, secure)
		writeJSON(w, http.StatusOK, adminSessionResponseFrom(session))
	}
}

func (s adminServices) logoutHandler() http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		session, ok := auth.AdminSessionFromContext(r.Context())
		if !ok {
			writeJSONError(w, http.StatusUnauthorized, "unauthorized", "authentication required")
			return
		}
		if err := s.admins.Logout(r.Context(), session.Token); err != nil {
			writeJSONError(w, http.StatusUnauthorized, "unauthorized", "authentication required")
			return
		}
		secure := auth.RequestSecureCookies(r, s.secureCookies)
		auth.ClearSessionCookie(w, secure)
		w.WriteHeader(http.StatusNoContent)
	}
}

func (s adminServices) meHandler() http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		session, ok := auth.AdminSessionFromContext(r.Context())
		if !ok {
			writeJSONError(w, http.StatusUnauthorized, "unauthorized", "authentication required")
			return
		}
		writeJSON(w, http.StatusOK, adminSessionResponseFrom(session))
	}
}

func (s adminServices) listDevicesHandler() http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		devices, err := s.tokens.ListDevices(r.Context())
		if err != nil {
			writeJSONError(w, http.StatusInternalServerError, "internal_error", "internal error")
			return
		}
		items := make([]deviceResponse, 0, len(devices))
		for _, device := range devices {
			items = append(items, deviceResponseFrom(device))
		}
		writeJSON(w, http.StatusOK, deviceListResponse{Items: items})
	}
}

func (s adminServices) revokeDeviceHandler() http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		deviceID := strings.TrimSpace(r.PathValue("id"))
		if deviceID == "" {
			writeJSONError(w, http.StatusBadRequest, "invalid_request", "invalid request")
			return
		}
		if err := s.tokens.Revoke(r.Context(), deviceID); errors.Is(err, auth.ErrDeviceNotFound) {
			writeJSONError(w, http.StatusNotFound, "not_found", "device not found")
			return
		} else if err != nil {
			writeJSONError(w, http.StatusInternalServerError, "internal_error", "internal error")
			return
		}
		w.WriteHeader(http.StatusNoContent)
	}
}

func (s adminServices) createPairingHandler() http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		body, err := io.ReadAll(io.LimitReader(r.Body, maxAdminRequestBytes+1))
		if err != nil {
			writeJSONError(w, http.StatusBadRequest, "invalid_request", "invalid request")
			return
		}
		if len(body) > maxAdminRequestBytes {
			writeJSONError(w, http.StatusBadRequest, "invalid_request", "invalid request")
			return
		}
		var request createPairingRequest
		if err := json.Unmarshal(body, &request); err != nil {
			writeJSONError(w, http.StatusBadRequest, "invalid_request", "invalid request")
			return
		}
		serverURL := strings.TrimSpace(request.ServerURL)
		if serverURL == "" {
			serverURL = strings.TrimSpace(s.publicURL)
		}
		serverURL, err = auth.NormalizeServerURL(serverURL)
		if err != nil {
			writeJSONError(w, http.StatusBadRequest, "invalid_request", "invalid request")
			return
		}
		created, err := s.pairing.Create(r.Context(), request.DeviceName)
		if errors.Is(err, auth.ErrInvalidDeviceName) {
			writeJSONError(w, http.StatusBadRequest, "invalid_request", "invalid request")
			return
		}
		if err != nil {
			writeJSONError(w, http.StatusInternalServerError, "internal_error", "internal error")
			return
		}
		writeJSON(w, http.StatusCreated, createPairingResponse{
			ID:         created.ID,
			DeviceName: created.DeviceName,
			Code:       created.Code,
			ExpiresAt:  created.ExpiresAt.UTC().Format(time.RFC3339Nano),
			QRPayload: qrPayload{
				ServerURL: serverURL,
				Code:      created.Code,
			},
		})
	}
}

func decodeAdminCredentials(w http.ResponseWriter, r *http.Request) (adminCredentialsRequest, bool) {
	var request adminCredentialsRequest
	body, err := io.ReadAll(io.LimitReader(r.Body, maxAdminRequestBytes+1))
	if err != nil {
		writeJSONError(w, http.StatusBadRequest, "invalid_request", "invalid request")
		return request, false
	}
	if len(body) > maxAdminRequestBytes {
		writeJSONError(w, http.StatusBadRequest, "invalid_request", "invalid request")
		return request, false
	}
	if err := json.Unmarshal(body, &request); err != nil {
		writeJSONError(w, http.StatusBadRequest, "invalid_request", "invalid request")
		return request, false
	}
	if strings.TrimSpace(request.Username) == "" || request.Password == "" {
		writeJSONError(w, http.StatusBadRequest, "invalid_request", "invalid request")
		return request, false
	}
	return request, true
}

func adminSessionResponseFrom(session auth.AdminSession) adminSessionResponse {
	return adminSessionResponse{
		Username:  session.Username,
		CSRFToken: session.CSRFToken,
		ExpiresAt: session.ExpiresAt.UTC().Format(time.RFC3339Nano),
	}
}

func deviceResponseFrom(device auth.Device) deviceResponse {
	response := deviceResponse{
		ID:        device.ID,
		Name:      device.Name,
		CreatedAt: device.CreatedAt.UTC().Format(time.RFC3339Nano),
	}
	if device.LastUsedAt != nil {
		value := device.LastUsedAt.UTC().Format(time.RFC3339Nano)
		response.LastUsedAt = &value
	}
	if device.RevokedAt != nil {
		value := device.RevokedAt.UTC().Format(time.RFC3339Nano)
		response.RevokedAt = &value
	}
	return response
}
