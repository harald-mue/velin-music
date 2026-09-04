package admin

import (
	"encoding/base64"
	"encoding/json"
	"errors"
	"html/template"
	"net/http"
	"strings"
	"time"

	"github.com/harald-mue/velin-music/server/internal/auth"
	"github.com/harald-mue/velin-music/server/internal/library"
	qrcode "github.com/skip2/go-qrcode"
)

func (h *Handler) indexHandler() http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		needsSetup, err := h.admins.NeedsSetup(r.Context())
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		if needsSetup {
			h.redirect(w, r, "/admin/setup", http.StatusSeeOther)
			return
		}
		session, ok, err := h.loadSession(r)
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		if !ok {
			h.redirect(w, r, "/admin/login", http.StatusSeeOther)
			return
		}
		h.renderDashboard(w, session)
	}
}

func (h *Handler) setupGetHandler() http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		needsSetup, err := h.admins.NeedsSetup(r.Context())
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		if !needsSetup {
			h.redirect(w, r, "/admin/login", http.StatusSeeOther)
			return
		}
		h.render(w, "setup", pageData{
			Title: "Set up",
		})
	}
}

func (h *Handler) setupPostHandler() http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if err := r.ParseForm(); err != nil {
			h.renderSetupError(w, "Invalid request.")
			return
		}
		username := strings.TrimSpace(r.FormValue("username"))
		password := r.FormValue("password")
		session, err := h.admins.Setup(r.Context(), username, password)
		if errors.Is(err, auth.ErrSetupComplete) {
			h.redirect(w, r, "/admin/login", http.StatusSeeOther)
			return
		}
		if errors.Is(err, auth.ErrInvalidUsername) || errors.Is(err, auth.ErrInvalidPassword) {
			h.renderSetupError(w, "Enter a valid username and a password with at least 8 characters.")
			return
		}
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		secure := auth.RequestSecureCookies(r, h.cfg.SecureCookies)
		auth.SetSessionCookie(w, session, secure)
		h.redirect(w, r, "/admin/", http.StatusSeeOther)
	}
}

func (h *Handler) loginGetHandler() http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		needsSetup, err := h.admins.NeedsSetup(r.Context())
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		if needsSetup {
			h.redirect(w, r, "/admin/setup", http.StatusSeeOther)
			return
		}
		if session, ok, err := h.loadSession(r); err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		} else if ok {
			h.renderDashboard(w, session)
			return
		}
		h.render(w, "login", pageData{
			Title: "Sign in",
		})
	}
}

func (h *Handler) loginPostHandler() http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if err := r.ParseForm(); err != nil {
			h.renderLoginError(w, "Invalid request.")
			return
		}
		username := strings.TrimSpace(r.FormValue("username"))
		password := r.FormValue("password")
		session, err := h.admins.Login(r.Context(), username, password)
		if errors.Is(err, auth.ErrSetupRequired) {
			h.redirect(w, r, "/admin/setup", http.StatusSeeOther)
			return
		}
		if errors.Is(err, auth.ErrInvalidCredentials) {
			h.renderLoginError(w, "Invalid username or password.")
			return
		}
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		secure := auth.RequestSecureCookies(r, h.cfg.SecureCookies)
		auth.SetSessionCookie(w, session, secure)
		h.redirect(w, r, "/admin/", http.StatusSeeOther)
	}
}

func (h *Handler) logoutHandler() http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		session, ok := sessionFromContext(r.Context())
		if !ok {
			h.redirect(w, r, "/admin/login", http.StatusSeeOther)
			return
		}
		if err := r.ParseForm(); err != nil {
			http.Error(w, "invalid request", http.StatusBadRequest)
			return
		}
		if err := validateFormCSRF(session, r); err != nil {
			http.Error(w, "invalid csrf token", http.StatusForbidden)
			return
		}
		_ = h.admins.Logout(r.Context(), session.Token)
		secure := auth.RequestSecureCookies(r, h.cfg.SecureCookies)
		auth.ClearSessionCookie(w, secure)
		h.redirect(w, r, "/admin/login", http.StatusSeeOther)
	}
}

func (h *Handler) devicesGetHandler() http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		session, ok := sessionFromContext(r.Context())
		if !ok {
			h.redirect(w, r, "/admin/login", http.StatusSeeOther)
			return
		}
		devices, err := h.tokens.ListDevices(r.Context())
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		views := make([]deviceView, 0, len(devices))
		for _, device := range devices {
			view := deviceView{
				ID:        device.ID,
				Name:      device.Name,
				CreatedAt: formatTime(device.CreatedAt),
			}
			if device.LastUsedAt != nil {
				view.LastUsedAt = formatTime(*device.LastUsedAt)
			}
			if device.RevokedAt != nil {
				view.RevokedAt = formatTime(*device.RevokedAt)
			}
			views = append(views, view)
		}
		h.render(w, "devices", pageData{
			Title:         "Devices",
			Authenticated: true,
			Username:      session.Username,
			CSRFToken:     session.CSRFToken,
			Version:       h.cfg.Version,
			ActiveNav:     "devices",
			Devices:       views,
		})
	}
}

func (h *Handler) revokeDeviceHandler() http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		session, ok := sessionFromContext(r.Context())
		if !ok {
			h.redirect(w, r, "/admin/login", http.StatusSeeOther)
			return
		}
		if err := r.ParseForm(); err != nil {
			http.Error(w, "invalid request", http.StatusBadRequest)
			return
		}
		if err := validateFormCSRF(session, r); err != nil {
			http.Error(w, "invalid csrf token", http.StatusForbidden)
			return
		}
		deviceID := strings.TrimSpace(r.PathValue("id"))
		if err := h.tokens.Revoke(r.Context(), deviceID); errors.Is(err, auth.ErrDeviceNotFound) {
			h.renderDevicesError(w, session, "Device not found.")
			return
		} else if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		h.redirect(w, r, "/admin/devices", http.StatusSeeOther)
	}
}

func (h *Handler) pairingGetHandler() http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		session, ok := sessionFromContext(r.Context())
		if !ok {
			h.redirect(w, r, "/admin/login", http.StatusSeeOther)
			return
		}
		h.render(w, "pairing", pageData{
			Title:         "Pairing",
			Authenticated: true,
			Username:      session.Username,
			CSRFToken:     session.CSRFToken,
			Version:       h.cfg.Version,
			ActiveNav:     "pairing",
			ServerURL:     h.cfg.PublicURL,
		})
	}
}

func (h *Handler) pairingPostHandler() http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		session, ok := sessionFromContext(r.Context())
		if !ok {
			h.redirect(w, r, "/admin/login", http.StatusSeeOther)
			return
		}
		if err := r.ParseForm(); err != nil {
			h.renderPairingError(w, session, "Invalid request.")
			return
		}
		if err := validateFormCSRF(session, r); err != nil {
			http.Error(w, "invalid csrf token", http.StatusForbidden)
			return
		}
		serverURL := strings.TrimSpace(r.FormValue("server_url"))
		if serverURL == "" {
			serverURL = strings.TrimSpace(h.cfg.PublicURL)
		}
		serverURL, err := auth.NormalizeServerURL(serverURL)
		if err != nil {
			h.renderPairingError(w, session, "Enter a valid http:// or https:// server URL.")
			return
		}
		created, err := h.pairing.Create(r.Context(), r.FormValue("device_name"))
		if errors.Is(err, auth.ErrInvalidDeviceName) {
			h.renderPairingError(w, session, "Enter a valid device name.")
			return
		}
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		payload, err := json.Marshal(map[string]string{
			"server_url": serverURL,
			"code":       created.Code,
		})
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		qrPNG, err := qrcode.Encode(string(payload), qrcode.Low, 320)
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		h.render(w, "pairing_result", pageData{
			Title:             "Pairing code",
			Authenticated:     true,
			Username:          session.Username,
			CSRFToken:         session.CSRFToken,
			Version:           h.cfg.Version,
			ActiveNav:         "pairing",
			PairingDeviceName: created.DeviceName,
			PairingExpiresAt:  formatTime(created.ExpiresAt),
			PairingServerURL:  serverURL,
			PairingCode:       created.Code,
			PairingQRPayload:  string(payload),
			PairingQRImageURL: template.URL("data:image/png;base64," + base64.StdEncoding.EncodeToString(qrPNG)),
		})
	}
}

func (h *Handler) renderDashboard(w http.ResponseWriter, session auth.AdminSession) {
	h.render(w, "dashboard", pageData{
		Title:         "Dashboard",
		Authenticated: true,
		Username:      session.Username,
		CSRFToken:     session.CSRFToken,
		Version:       h.cfg.Version,
		ActiveNav:     "dashboard",
	})
}

func (h *Handler) renderSetupError(w http.ResponseWriter, message string) {
	h.render(w, "setup", pageData{
		Title: "Set up",
		Error: message,
	})
}

func (h *Handler) renderLoginError(w http.ResponseWriter, message string) {
	h.render(w, "login", pageData{
		Title: "Sign in",
		Error: message,
	})
}

func (h *Handler) renderDevicesError(w http.ResponseWriter, session auth.AdminSession, message string) {
	h.render(w, "devices", pageData{
		Title:         "Devices",
		Authenticated: true,
		Username:      session.Username,
		CSRFToken:     session.CSRFToken,
		Version:       h.cfg.Version,
		ActiveNav:     "devices",
		Error:         message,
	})
}

func (h *Handler) renderPairingError(w http.ResponseWriter, session auth.AdminSession, message string) {
	h.render(w, "pairing", pageData{
		Title:         "Pairing",
		Authenticated: true,
		Username:      session.Username,
		CSRFToken:     session.CSRFToken,
		Version:       h.cfg.Version,
		ActiveNav:     "pairing",
		ServerURL:     h.cfg.PublicURL,
		Error:         message,
	})
}

func formatTime(value time.Time) string {
	return value.UTC().Format(time.RFC3339)
}

func (h *Handler) rootsGetHandler() http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		session, ok := sessionFromContext(r.Context())
		if !ok {
			h.redirect(w, r, "/admin/login", http.StatusSeeOther)
			return
		}
		h.renderRootsPage(w, r, session, "")
	}
}

func (h *Handler) rootsPostHandler() http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		session, ok := sessionFromContext(r.Context())
		if !ok {
			h.redirect(w, r, "/admin/login", http.StatusSeeOther)
			return
		}
		if err := r.ParseForm(); err != nil {
			h.renderRootsPage(w, r, session, "Invalid request.")
			return
		}
		if err := validateFormCSRF(session, r); err != nil {
			http.Error(w, "invalid csrf token", http.StatusForbidden)
			return
		}
		path := strings.TrimSpace(r.FormValue("path"))
		if path == "" {
			h.renderRootsPage(w, r, session, "Enter a library root path.")
			return
		}
		if _, err := h.roots.AddRoot(r.Context(), path); errors.Is(err, library.ErrRootExists) {
			h.renderRootsPage(w, r, session, "That library root is already configured.")
			return
		} else if err != nil {
			h.renderRootsPage(w, r, session, "Could not add library root. Check that the path exists and is a directory.")
			return
		}
		h.redirect(w, r, "/admin/roots", http.StatusSeeOther)
	}
}

func (h *Handler) removeRootHandler() http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		session, ok := sessionFromContext(r.Context())
		if !ok {
			h.redirect(w, r, "/admin/login", http.StatusSeeOther)
			return
		}
		if err := r.ParseForm(); err != nil {
			http.Error(w, "invalid request", http.StatusBadRequest)
			return
		}
		if err := validateFormCSRF(session, r); err != nil {
			http.Error(w, "invalid csrf token", http.StatusForbidden)
			return
		}
		rootID := strings.TrimSpace(r.PathValue("id"))
		if err := h.roots.RemoveRoot(r.Context(), rootID); errors.Is(err, library.ErrRootNotFound) {
			h.renderRootsPage(w, r, session, "Library root not found.")
			return
		} else if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		h.redirect(w, r, "/admin/roots", http.StatusSeeOther)
	}
}

func (h *Handler) scanRootHandler() http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		session, ok := sessionFromContext(r.Context())
		if !ok {
			h.redirect(w, r, "/admin/login", http.StatusSeeOther)
			return
		}
		if err := r.ParseForm(); err != nil {
			http.Error(w, "invalid request", http.StatusBadRequest)
			return
		}
		if err := validateFormCSRF(session, r); err != nil {
			http.Error(w, "invalid csrf token", http.StatusForbidden)
			return
		}
		rootID := strings.TrimSpace(r.PathValue("id"))
		if err := h.scans.StartRoot(rootID); errors.Is(err, library.ErrRootNotFound) {
			h.renderRootsPage(w, r, session, "Library root not found.")
			return
		} else if errors.Is(err, library.ErrScanAlreadyRunning) {
			h.renderRootsPage(w, r, session, "A scan is already running for that root.")
			return
		} else if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		h.redirect(w, r, "/admin/roots", http.StatusSeeOther)
	}
}

func (h *Handler) scanAllHandler() http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		session, ok := sessionFromContext(r.Context())
		if !ok {
			h.redirect(w, r, "/admin/login", http.StatusSeeOther)
			return
		}
		if err := r.ParseForm(); err != nil {
			http.Error(w, "invalid request", http.StatusBadRequest)
			return
		}
		if err := validateFormCSRF(session, r); err != nil {
			http.Error(w, "invalid csrf token", http.StatusForbidden)
			return
		}
		if err := h.scans.StartAll(); errors.Is(err, library.ErrScanAlreadyRunning) {
			h.renderRootsPage(w, r, session, "A full-library scan is already running.")
			return
		} else if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		h.redirect(w, r, "/admin/roots", http.StatusSeeOther)
	}
}

func (h *Handler) renderRootsPage(w http.ResponseWriter, r *http.Request, session auth.AdminSession, message string) {
	roots, err := h.roots.ListRoots(r.Context())
	if err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	views := make([]rootView, 0, len(roots))
	for _, root := range roots {
		view := rootView{
			ID:        root.ID,
			Path:      root.Path,
			CreatedAt: formatTime(root.CreatedAt),
		}
		latest, err := h.scanQueries.LatestForRoot(r.Context(), root.ID)
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		if latest != nil {
			view.LatestScanStatus = latest.Status
			view.LatestScanStarted = formatTime(latest.StartedAt)
			if latest.FinishedAt != nil {
				view.LatestScanFinished = formatTime(*latest.FinishedAt)
			}
			view.ScanRunning = latest.Status == "running"
		}
		views = append(views, view)
	}
	recent, err := h.scanQueries.ListRecent(r.Context(), 10)
	if err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	scanViews := make([]scanView, 0, len(recent))
	for _, run := range recent {
		view := scanView{
			ID:           run.ID,
			RootID:       run.RootID,
			Status:       run.Status,
			StartedAt:    formatTime(run.StartedAt),
			FilesSeen:    run.FilesSeen,
			FilesIndexed: run.FilesIndexed,
			FilesRemoved: run.FilesRemoved,
		}
		if run.FinishedAt != nil {
			view.FinishedAt = formatTime(*run.FinishedAt)
		}
		scanViews = append(scanViews, view)
	}
	h.render(w, "roots", pageData{
		Title:         "Library roots",
		Authenticated: true,
		Username:      session.Username,
		CSRFToken:     session.CSRFToken,
		Version:       h.cfg.Version,
		ActiveNav:     "roots",
		Error:         message,
		Roots:         views,
		RecentScans:   scanViews,
	})
}

func (h *Handler) scanDetailHandler() http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		session, ok := sessionFromContext(r.Context())
		if !ok {
			h.redirect(w, r, "/admin/login", http.StatusSeeOther)
			return
		}
		scanID := strings.TrimSpace(r.PathValue("id"))
		if scanID == "" {
			http.Error(w, "not found", http.StatusNotFound)
			return
		}
		run, err := h.scanQueries.GetByID(r.Context(), scanID)
		if errors.Is(err, library.ErrScanRunNotFound) {
			http.Error(w, "not found", http.StatusNotFound)
			return
		}
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		errorTotal, err := h.scanQueries.CountErrors(r.Context(), scanID)
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		errorsList, err := h.scanQueries.ListErrors(r.Context(), scanID, 200)
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		errorViews := make([]scanErrorView, 0, len(errorsList))
		for _, item := range errorsList {
			errorViews = append(errorViews, scanErrorView{
				SourceName: item.SourceName,
				ErrorCode:  item.ErrorCode,
				Message:    item.Message,
				CreatedAt:  formatTime(item.CreatedAt),
			})
		}
		scan := scanViewFromRecord(*run)
		h.render(w, "scan_detail", pageData{
			Title:          "Scan details",
			Authenticated:  true,
			Username:       session.Username,
			CSRFToken:      session.CSRFToken,
			Version:        h.cfg.Version,
			ActiveNav:      "roots",
			Scan:           scan,
			ScanErrors:     errorViews,
			ScanErrorTotal: errorTotal,
		})
	}
}

func (h *Handler) searchGetHandler() http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		session, ok := sessionFromContext(r.Context())
		if !ok {
			h.redirect(w, r, "/admin/login", http.StatusSeeOther)
			return
		}
		query := strings.TrimSpace(r.URL.Query().Get("q"))
		data := pageData{
			Title:         "Search diagnostics",
			Authenticated: true,
			Username:      session.Username,
			CSRFToken:     session.CSRFToken,
			Version:       h.cfg.Version,
			ActiveNav:     "search",
			SearchQuery:   query,
		}
		if query == "" {
			h.render(w, "search", data)
			return
		}
		diag := library.DiagnoseSearchQuery(query)
		data.SearchDiag = searchDiagView{
			Valid:      diag.Valid,
			InputBytes: diag.InputBytes,
			Terms:      diag.Terms,
			MatchQuery: diag.MatchQuery,
			Scope:      diag.Scope,
			Message:    diag.Message,
		}
		if !diag.Valid {
			h.render(w, "search", data)
			return
		}
		page, err := h.queries.SearchTracks(r.Context(), query, library.PageOptions{Limit: 10})
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		results := make([]searchResultView, 0, len(page.Items))
		for _, track := range page.Items {
			results = append(results, searchResultView{
				ID:     track.ID,
				Title:  track.Title,
				Artist: optionalString(track.ArtistName),
				Album:  optionalString(track.AlbumTitle),
			})
		}
		data.SearchResults = results
		data.SearchResultCount = len(results)
		data.SearchHasMore = page.HasMore
		h.render(w, "search", data)
	}
}

func scanViewFromRecord(run library.ScanRunRecord) scanView {
	view := scanView{
		ID:           run.ID,
		RootID:       run.RootID,
		Status:       run.Status,
		StartedAt:    formatTime(run.StartedAt),
		FilesSeen:    run.FilesSeen,
		FilesIndexed: run.FilesIndexed,
		FilesRemoved: run.FilesRemoved,
	}
	if run.FinishedAt != nil {
		view.FinishedAt = formatTime(*run.FinishedAt)
	}
	return view
}

func optionalString(value *string) string {
	if value == nil {
		return ""
	}
	return *value
}
