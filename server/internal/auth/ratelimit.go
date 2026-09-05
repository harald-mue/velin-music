package auth

import (
	"net"
	"net/http"
	"strings"
	"sync"
	"time"
)

// RateLimiter enforces a bounded request count per key within a sliding window.
const maxRateLimitKeys = 10000

type RateLimiter struct {
	mu          sync.Mutex
	limit       int
	window      time.Duration
	hits        map[string][]time.Time
	lastCleanup time.Time
}

// NewRateLimiter creates a limiter that allows limit hits per window duration.
func NewRateLimiter(limit int, window time.Duration) *RateLimiter {
	return &RateLimiter{
		limit:  limit,
		window: window,
		hits:   make(map[string][]time.Time),
	}
}

// Allow reports whether key may proceed and records the attempt when allowed.
func (l *RateLimiter) Allow(key string) bool {
	if l == nil || l.limit < 1 {
		return true
	}
	now := time.Now()
	cutoff := now.Add(-l.window)

	l.mu.Lock()
	defer l.mu.Unlock()

	if l.lastCleanup.IsZero() || now.Sub(l.lastCleanup) >= l.window {
		for existingKey, existingHits := range l.hits {
			kept := existingHits[:0]
			for _, timestamp := range existingHits {
				if timestamp.After(cutoff) {
					kept = append(kept, timestamp)
				}
			}
			if len(kept) == 0 {
				delete(l.hits, existingKey)
			} else {
				l.hits[existingKey] = kept
			}
		}
		l.lastCleanup = now
	}
	if _, exists := l.hits[key]; !exists && len(l.hits) >= maxRateLimitKeys {
		return false
	}

	timestamps := l.hits[key]
	filtered := timestamps[:0]
	for _, timestamp := range timestamps {
		if timestamp.After(cutoff) {
			filtered = append(filtered, timestamp)
		}
	}
	if len(filtered) >= l.limit {
		l.hits[key] = filtered
		return false
	}
	filtered = append(filtered, now)
	l.hits[key] = filtered
	return true
}

// Limit wraps a handler with a per-remote-address rate limit.
func (l *RateLimiter) Limit(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !l.Allow(clientKey(r)) {
			writeJSONError(w, http.StatusTooManyRequests, "rate_limited", "too many requests")
			return
		}
		next.ServeHTTP(w, r)
	})
}

func clientKey(r *http.Request) string {
	remoteAddress := strings.TrimSpace(r.RemoteAddr)
	host, _, err := net.SplitHostPort(remoteAddress)
	if err == nil && host != "" {
		return host
	}
	return remoteAddress
}
