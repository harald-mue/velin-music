package admin

import (
	"context"
	"net/http"

	"github.com/harald-mue/velin-music/server/internal/auth"
)

type sessionContextKey struct{}

func sessionFromContext(ctx context.Context) (auth.AdminSession, bool) {
	if ctx == nil {
		return auth.AdminSession{}, false
	}
	session, ok := ctx.Value(sessionContextKey{}).(auth.AdminSession)
	return session, ok && session.ID != ""
}

func (h *Handler) requireSession(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		session, ok, err := h.loadSession(r)
		if err != nil {
			http.Error(w, "internal error", http.StatusInternalServerError)
			return
		}
		if !ok {
			h.redirect(w, r, "/admin/login", http.StatusSeeOther)
			return
		}
		ctx := context.WithValue(r.Context(), sessionContextKey{}, session)
		next.ServeHTTP(w, r.WithContext(ctx))
	})
}

func (h *Handler) loadSession(r *http.Request) (auth.AdminSession, bool, error) {
	cookie, err := r.Cookie(auth.AdminSessionCookie)
	if err != nil || cookie.Value == "" {
		return auth.AdminSession{}, false, nil
	}
	session, err := h.admins.AuthenticateSession(r.Context(), cookie.Value)
	if err != nil {
		return auth.AdminSession{}, false, nil
	}
	return session, true, nil
}

func validateFormCSRF(session auth.AdminSession, r *http.Request) error {
	token := r.Header.Get(auth.AdminCSRFHeader)
	if token == "" {
		token = r.FormValue("csrf_token")
	}
	return auth.ValidateCSRF(session, token)
}
