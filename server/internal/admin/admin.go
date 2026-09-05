package admin

import (
	"html/template"
	"io/fs"
	"net/http"

	"github.com/harald-mue/velin-music/server/internal/auth"
	"github.com/harald-mue/velin-music/server/internal/library"
)

// Config contains administration UI settings.
type Config struct {
	Version       string
	PublicURL     string
	SecureCookies bool
}

// Handler serves the server-rendered administration UI.
type Handler struct {
	cfg         Config
	admins      *auth.AdminRepository
	tokens      *auth.TokenRepository
	pairing     *auth.PairingRepository
	loginLim    *auth.RateLimiter
	roots       *library.Store
	scans       *library.ScanService
	scanQueries *library.ScanQueryRepository
	queries     *library.QueryRepository
	tmpl        *template.Template
	static      http.Handler
}

// New creates an administration UI handler.
func New(cfg Config, admins *auth.AdminRepository, tokens *auth.TokenRepository, pairing *auth.PairingRepository, loginLimiter *auth.RateLimiter, roots *library.Store, scans *library.ScanService, scanQueries *library.ScanQueryRepository, queries *library.QueryRepository) (*Handler, error) {
	tmpl, err := parseTemplates()
	if err != nil {
		return nil, err
	}
	staticFS, err := fs.Sub(assets, "static")
	if err != nil {
		return nil, err
	}
	return &Handler{
		cfg:         cfg,
		admins:      admins,
		tokens:      tokens,
		pairing:     pairing,
		loginLim:    loginLimiter,
		roots:       roots,
		scans:       scans,
		scanQueries: scanQueries,
		queries:     queries,
		tmpl:        tmpl,
		static:      http.FileServer(http.FS(staticFS)),
	}, nil
}

// Register mounts administration routes on mux.
func (h *Handler) Register(mux *http.ServeMux) {
	secure := h.withSecurityHeaders
	mux.Handle("GET /admin/static/", secure(http.StripPrefix("/admin/static/", h.static)))

	mux.Handle("GET /admin", secure(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		h.redirect(w, r, "/admin/", http.StatusMovedPermanently)
	})))
	mux.Handle("GET /admin/", secure(http.HandlerFunc(h.indexHandler())))
	mux.Handle("GET /admin/setup", secure(h.loginLim.Limit(http.HandlerFunc(h.setupGetHandler()))))
	mux.Handle("POST /admin/setup", secure(h.loginLim.Limit(http.HandlerFunc(h.setupPostHandler()))))
	mux.Handle("GET /admin/login", secure(h.loginLim.Limit(http.HandlerFunc(h.loginGetHandler()))))
	mux.Handle("POST /admin/login", secure(h.loginLim.Limit(http.HandlerFunc(h.loginPostHandler()))))
	mux.Handle("POST /admin/logout", secure(h.requireSession(http.HandlerFunc(h.logoutHandler()))))
	mux.Handle("GET /admin/devices", secure(h.requireSession(http.HandlerFunc(h.devicesGetHandler()))))
	mux.Handle("POST /admin/devices/{id}/revoke", secure(h.requireSession(http.HandlerFunc(h.revokeDeviceHandler()))))
	mux.Handle("GET /admin/pairing", secure(h.requireSession(http.HandlerFunc(h.pairingGetHandler()))))
	mux.Handle("POST /admin/pairing", secure(h.requireSession(http.HandlerFunc(h.pairingPostHandler()))))
	mux.Handle("GET /admin/roots", secure(h.requireSession(http.HandlerFunc(h.rootsGetHandler()))))
	mux.Handle("POST /admin/roots", secure(h.requireSession(http.HandlerFunc(h.rootsPostHandler()))))
	mux.Handle("POST /admin/roots/{id}/remove", secure(h.requireSession(http.HandlerFunc(h.removeRootHandler()))))
	mux.Handle("POST /admin/roots/{id}/scan", secure(h.requireSession(http.HandlerFunc(h.scanRootHandler()))))
	mux.Handle("POST /admin/scans", secure(h.requireSession(http.HandlerFunc(h.scanAllHandler()))))
	mux.Handle("GET /admin/scans/{id}", secure(h.requireSession(http.HandlerFunc(h.scanDetailHandler()))))
	mux.Handle("GET /admin/search", secure(h.requireSession(http.HandlerFunc(h.searchGetHandler()))))
}

func (h *Handler) withSecurityHeaders(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("Content-Security-Policy", "default-src 'none'; base-uri 'none'; connect-src 'self'; form-action 'self'; frame-ancestors 'none'; img-src 'self' data:; object-src 'none'; script-src 'self'; style-src 'self'")
		w.Header().Set("Cross-Origin-Opener-Policy", "same-origin")
		w.Header().Set("Cross-Origin-Resource-Policy", "same-origin")
		w.Header().Set("Permissions-Policy", "camera=(), geolocation=(), microphone=()")
		w.Header().Set("Referrer-Policy", "no-referrer")
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("X-Frame-Options", "DENY")
		next.ServeHTTP(w, r)
	})
}
