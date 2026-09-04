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
	cfg          Config
	admins       *auth.AdminRepository
	tokens       *auth.TokenRepository
	pairing      *auth.PairingRepository
	loginLim     *auth.RateLimiter
	roots        *library.Store
	scans        *library.ScanService
	scanQueries  *library.ScanQueryRepository
	queries      *library.QueryRepository
	tmpl         *template.Template
	static       http.Handler
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
	mux.Handle("GET /admin/static/", http.StripPrefix("/admin/static/", h.static))

	mux.HandleFunc("GET /admin/", h.indexHandler())
	mux.Handle("GET /admin/setup", h.loginLim.Limit(http.HandlerFunc(h.setupGetHandler())))
	mux.Handle("POST /admin/setup", h.loginLim.Limit(http.HandlerFunc(h.setupPostHandler())))
	mux.Handle("GET /admin/login", h.loginLim.Limit(http.HandlerFunc(h.loginGetHandler())))
	mux.Handle("POST /admin/login", h.loginLim.Limit(http.HandlerFunc(h.loginPostHandler())))
	mux.Handle("POST /admin/logout", h.requireSession(http.HandlerFunc(h.logoutHandler())))
	mux.Handle("GET /admin/devices", h.requireSession(http.HandlerFunc(h.devicesGetHandler())))
	mux.Handle("POST /admin/devices/{id}/revoke", h.requireSession(http.HandlerFunc(h.revokeDeviceHandler())))
	mux.Handle("GET /admin/pairing", h.requireSession(http.HandlerFunc(h.pairingGetHandler())))
	mux.Handle("POST /admin/pairing", h.requireSession(http.HandlerFunc(h.pairingPostHandler())))
	mux.Handle("GET /admin/roots", h.requireSession(http.HandlerFunc(h.rootsGetHandler())))
	mux.Handle("POST /admin/roots", h.requireSession(http.HandlerFunc(h.rootsPostHandler())))
	mux.Handle("POST /admin/roots/{id}/remove", h.requireSession(http.HandlerFunc(h.removeRootHandler())))
	mux.Handle("POST /admin/roots/{id}/scan", h.requireSession(http.HandlerFunc(h.scanRootHandler())))
	mux.Handle("POST /admin/scans", h.requireSession(http.HandlerFunc(h.scanAllHandler())))
	mux.Handle("GET /admin/scans/{id}", h.requireSession(http.HandlerFunc(h.scanDetailHandler())))
	mux.Handle("GET /admin/search", h.requireSession(http.HandlerFunc(h.searchGetHandler())))
}
