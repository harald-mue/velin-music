package httpapi

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"strings"
	"time"

	"github.com/harald-mue/velin-music/server/internal/admin"
	"github.com/harald-mue/velin-music/server/internal/auth"
	"github.com/harald-mue/velin-music/server/internal/library"
)

const maxPairRequestBytes = 4096

// Config contains HTTP API settings.
type Config struct {
	Version       string
	PublicURL     string
	SecureCookies bool
	DataDir       string
	ScanOnStartup bool
	ScanInterval  time.Duration
}

// Server owns the HTTP handler and background library services.
type Server struct {
	handler   http.Handler
	scheduler *library.ScanScheduler
}

// Handler returns the HTTP handler served by the API.
func (s *Server) Handler() http.Handler {
	if s == nil {
		return nil
	}
	return s.handler
}

// Stop shuts down background library services.
func (s *Server) Stop() {
	if s == nil || s.scheduler == nil {
		return
	}
	s.scheduler.Stop()
}

// New returns the configured HTTP API server.
func New(cfg Config, database *sql.DB) (*Server, error) {
	cache, err := library.NewArtworkCache(cfg.DataDir)
	if err != nil {
		return nil, err
	}
	maintenance, err := library.RunStartupMaintenance(context.Background(), database, cache)
	if err != nil {
		return nil, err
	}
	if maintenance.RecoveredScans > 0 || maintenance.RemovedCacheFiles > 0 {
		slog.Info("library startup maintenance complete",
			"recovered_scans", maintenance.RecoveredScans,
			"removed_cache_files", maintenance.RemovedCacheFiles,
		)
	}
	mux := http.NewServeMux()
	tokens := auth.NewTokenRepository(database)
	pairing := auth.NewPairingRepository(database)
	admins := auth.NewAdminRepository(database)
	queries := library.NewQueryRepository(database)
	roots := library.NewStore(database)
	covers := library.NewCoverStore(database, cache)
	tracks := library.NewTrackRepositoryWithArtwork(database, covers)
	scanner := library.NewScanner(roots, tracks)
	scanQueries := library.NewScanQueryRepository(database)
	scanService := library.NewScanService(scanner, roots, scanQueries)
	pairLimiter := auth.NewRateLimiter(20, auth.PairingRateWindow)
	loginLimiter := auth.NewRateLimiter(10, auth.AdminLoginWindow)

	mux.HandleFunc("GET /api/v1/status", statusHandler(cfg.Version))
	mux.Handle("POST /api/v1/pair", pairLimiter.Limit(http.HandlerFunc(pairHandler(cfg.Version, pairing, tokens))))

	registerLibraryRoutes(mux, libraryServices{
		tokens:  tokens,
		queries: queries,
		covers:  library.NewCoverReader(database, cache),
		streams: library.NewTrackStreamer(database),
	})
	registerAdminRoutes(mux, adminServices{
		version:       cfg.Version,
		publicURL:     cfg.PublicURL,
		secureCookies: cfg.SecureCookies,
		admins:        admins,
		tokens:        tokens,
		pairing:       pairing,
		loginLimiter:  loginLimiter,
		roots:         roots,
		scans:         scanService,
		scanQueries:   scanQueries,
		queries:       queries,
	})
	adminUI, err := admin.New(admin.Config{
		Version:       cfg.Version,
		PublicURL:     cfg.PublicURL,
		SecureCookies: cfg.SecureCookies,
	}, admins, tokens, pairing, loginLimiter, roots, scanService, scanQueries, queries)
	if err != nil {
		return nil, err
	}
	adminUI.Register(mux)

	if cfg.ScanOnStartup {
		go func() {
			if !scanService.TryStartAll() {
				slog.Info("startup scan skipped", "reason", "scan_already_running")
			}
		}()
	}

	var scheduler *library.ScanScheduler
	if cfg.ScanInterval > 0 {
		scheduler = library.NewScanScheduler(scanService, cfg.ScanInterval)
		scheduler.Start()
	}

	return &Server{handler: mux, scheduler: scheduler}, nil
}

type statusResponse struct {
	Name    string `json:"name"`
	Status  string `json:"status"`
	Version string `json:"version"`
}

type pairRequest struct {
	Code string `json:"code"`
}

type pairResponse struct {
	DeviceID string         `json:"device_id"`
	Token    string         `json:"token"`
	Server   statusResponse `json:"server"`
}

func statusHandler(version string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, http.StatusOK, statusResponse{
			Name:    "Velin",
			Status:  "ok",
			Version: version,
		})
	}
}

func pairHandler(version string, pairing *auth.PairingRepository, tokens *auth.TokenRepository) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			writeJSONError(w, http.StatusMethodNotAllowed, "method_not_allowed", "method not allowed")
			return
		}
		body, err := io.ReadAll(io.LimitReader(r.Body, maxPairRequestBytes+1))
		if err != nil {
			writeJSONError(w, http.StatusBadRequest, "invalid_request", "invalid request")
			return
		}
		if len(body) > maxPairRequestBytes {
			writeJSONError(w, http.StatusBadRequest, "invalid_request", "invalid request")
			return
		}

		var request pairRequest
		if err := json.Unmarshal(body, &request); err != nil {
			writeJSONError(w, http.StatusBadRequest, "invalid_request", "invalid request")
			return
		}
		if strings.TrimSpace(request.Code) == "" {
			writeJSONError(w, http.StatusBadRequest, "invalid_request", "invalid request")
			return
		}

		issued, err := pairing.Exchange(r.Context(), request.Code, tokens)
		if errors.Is(err, auth.ErrInvalidPairingCode) {
			writeJSONError(w, http.StatusUnauthorized, "invalid_pairing_code", "invalid pairing code")
			return
		}
		if err != nil {
			writeJSONError(w, http.StatusInternalServerError, "internal_error", "internal error")
			return
		}

		writeJSON(w, http.StatusOK, pairResponse{
			DeviceID: issued.DeviceID,
			Token:    issued.Token,
			Server: statusResponse{
				Name:    "Velin",
				Status:  "ok",
				Version: version,
			},
		})
	}
}

// Protected returns a handler that requires a valid device bearer token.
func Protected(tokens *auth.TokenRepository, handler http.Handler) http.Handler {
	return auth.RequireDeviceToken(tokens)(handler)
}

func writeJSON(w http.ResponseWriter, status int, payload any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(payload)
}

func writeJSONError(w http.ResponseWriter, status int, code, message string) {
	writeJSON(w, status, map[string]any{
		"error": map[string]string{
			"code":    code,
			"message": message,
		},
	})
}
