package auth

import (
	"context"
	"net/http"
)

const adminSessionKey contextKey = "adminSession"

// AdminSessionFromContext returns the authenticated admin session when present.
func AdminSessionFromContext(ctx context.Context) (AdminSession, bool) {
	if ctx == nil {
		return AdminSession{}, false
	}
	session, ok := ctx.Value(adminSessionKey).(AdminSession)
	return session, ok && session.ID != ""
}

// RequireAdminSession validates the session cookie before calling next.
func RequireAdminSession(admins *AdminRepository) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			token, err := readSessionCookie(r)
			if err != nil {
				writeJSONError(w, http.StatusUnauthorized, "unauthorized", "authentication required")
				return
			}
			session, err := admins.AuthenticateSession(r.Context(), token)
			if err != nil {
				writeJSONError(w, http.StatusUnauthorized, "unauthorized", "authentication required")
				return
			}
			ctx := context.WithValue(r.Context(), adminSessionKey, session)
			next.ServeHTTP(w, r.WithContext(ctx))
		})
	}
}

// RequireAdminCSRF validates the CSRF header for mutating admin requests.
func RequireAdminCSRF(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		session, ok := AdminSessionFromContext(r.Context())
		if !ok {
			writeJSONError(w, http.StatusUnauthorized, "unauthorized", "authentication required")
			return
		}
		if err := ValidateCSRF(session, r.Header.Get(AdminCSRFHeader)); err != nil {
			writeJSONError(w, http.StatusForbidden, "invalid_csrf", "invalid csrf token")
			return
		}
		next.ServeHTTP(w, r)
	})
}

func readSessionCookie(r *http.Request) (string, error) {
	cookie, err := r.Cookie(AdminSessionCookie)
	if err != nil || cookie.Value == "" {
		return "", ErrInvalidSession
	}
	return cookie.Value, nil
}

func SetSessionCookie(w http.ResponseWriter, session AdminSession, secure bool) {
	http.SetCookie(w, &http.Cookie{
		Name:     AdminSessionCookie,
		Value:    session.Token,
		Path:     "/",
		HttpOnly: true,
		SameSite: http.SameSiteLaxMode,
		Secure:   secure,
		Expires:  session.ExpiresAt,
	})
}

func ClearSessionCookie(w http.ResponseWriter, secure bool) {
	http.SetCookie(w, &http.Cookie{
		Name:     AdminSessionCookie,
		Value:    "",
		Path:     "/",
		HttpOnly: true,
		SameSite: http.SameSiteLaxMode,
		Secure:   secure,
		MaxAge:   -1,
	})
}

func RequestSecureCookies(r *http.Request, configured bool) bool {
	if configured {
		return true
	}
	if r.TLS != nil {
		return true
	}
	return r.Header.Get("X-Forwarded-Proto") == "https"
}
