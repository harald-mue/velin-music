package auth

import (
	"context"
	"net/http"
)

type contextKey string

const deviceIDKey contextKey = "deviceID"

// DeviceIDFromContext returns the authenticated device ID when present.
func DeviceIDFromContext(ctx context.Context) (string, bool) {
	if ctx == nil {
		return "", false
	}
	deviceID, ok := ctx.Value(deviceIDKey).(string)
	return deviceID, ok && deviceID != ""
}

// RequireDeviceToken validates bearer tokens before calling next.
func RequireDeviceToken(tokens *TokenRepository) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			token, err := parseBearerToken(r.Header.Get("Authorization"))
			if err != nil {
				writeJSONError(w, http.StatusUnauthorized, "unauthorized", "authentication required")
				return
			}
			deviceID, err := tokens.Authenticate(r.Context(), token)
			if err != nil {
				writeJSONError(w, http.StatusUnauthorized, "unauthorized", "authentication required")
				return
			}
			ctx := context.WithValue(r.Context(), deviceIDKey, deviceID)
			next.ServeHTTP(w, r.WithContext(ctx))
		})
	}
}

func writeJSONError(w http.ResponseWriter, status int, code, message string) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_, _ = w.Write([]byte(`{"error":{"code":"` + code + `","message":"` + message + `"}}`))
}
