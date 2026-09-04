package auth

import (
	"context"
	"crypto/subtle"
	"database/sql"
	"errors"
	"fmt"
	"strings"
	"time"
)

const (
	AdminSessionCookie = "velin_admin_session"
	AdminCSRFHeader    = "X-CSRF-Token"
	AdminLoginWindow   = time.Minute
)

// AdminUser is a registered administrator without secret material.
type AdminUser struct {
	ID        string
	Username  string
	CreatedAt time.Time
}

// AdminSession is an authenticated browser session.
type AdminSession struct {
	ID        string
	UserID    string
	Username  string
	Token     string
	CSRFToken string
	ExpiresAt time.Time
}

// AdminRepository stores administrator credentials and browser sessions.
type AdminRepository struct {
	db *sql.DB
}

// NewAdminRepository creates a repository backed by db.
func NewAdminRepository(db *sql.DB) *AdminRepository {
	return &AdminRepository{db: db}
}

// NeedsSetup reports whether no administrator account exists yet.
func (r *AdminRepository) NeedsSetup(ctx context.Context) (bool, error) {
	if err := r.check(ctx); err != nil {
		return false, err
	}
	var count int
	if err := r.db.QueryRowContext(ctx, `SELECT COUNT(*) FROM admin_users`).Scan(&count); err != nil {
		return false, fmt.Errorf("count admin users: %w", err)
	}
	return count == 0, nil
}

// Setup creates the first administrator and returns a browser session.
func (r *AdminRepository) Setup(ctx context.Context, username, password string) (AdminSession, error) {
	var session AdminSession
	if err := r.check(ctx); err != nil {
		return session, err
	}
	needsSetup, err := r.NeedsSetup(ctx)
	if err != nil {
		return session, err
	}
	if !needsSetup {
		return session, ErrSetupComplete
	}

	username, err = normalizeUsername(username)
	if err != nil {
		return session, err
	}
	password, err = normalizePassword(password)
	if err != nil {
		return session, err
	}
	passwordHash, err := hashAdminPassword(password)
	if err != nil {
		return session, fmt.Errorf("hash admin password: %w", err)
	}

	userID, err := newDeviceID()
	if err != nil {
		return session, fmt.Errorf("generate admin user ID: %w", err)
	}
	now := time.Now().UTC().Format(time.RFC3339Nano)
	if _, err := r.db.ExecContext(ctx, `
		INSERT INTO admin_users (id, username, password_hash, created_at)
		VALUES (?, ?, ?, ?)`, userID, username, passwordHash, now); err != nil {
		return session, fmt.Errorf("store admin user: %w", err)
	}
	return r.createSession(ctx, userID, username)
}

// Login verifies credentials and returns a browser session.
func (r *AdminRepository) Login(ctx context.Context, username, password string) (AdminSession, error) {
	var session AdminSession
	if err := r.check(ctx); err != nil {
		return session, err
	}
	needsSetup, err := r.NeedsSetup(ctx)
	if err != nil {
		return session, err
	}
	if needsSetup {
		return session, ErrSetupRequired
	}

	username, err = normalizeUsername(username)
	if err != nil {
		return session, ErrInvalidCredentials
	}
	password, err = normalizePassword(password)
	if err != nil {
		return session, ErrInvalidCredentials
	}

	var userID, passwordHash string
	if err := r.db.QueryRowContext(ctx, `
		SELECT id, password_hash
		FROM admin_users
		WHERE username = ?`, username).Scan(&userID, &passwordHash); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return session, ErrInvalidCredentials
		}
		return session, fmt.Errorf("load admin user: %w", err)
	}
	if !verifyAdminPassword(passwordHash, password) {
		return session, ErrInvalidCredentials
	}
	return r.createSession(ctx, userID, username)
}

// AuthenticateSession validates a session token and returns the active session.
func (r *AdminRepository) AuthenticateSession(ctx context.Context, token string) (AdminSession, error) {
	var session AdminSession
	if err := r.check(ctx); err != nil {
		return session, err
	}
	token = strings.TrimSpace(token)
	if token == "" {
		return session, ErrInvalidSession
	}
	tokenHash := hashSessionToken(token)
	var (
		sessionID string
		userID    string
		username  string
		csrfToken string
		expiresAt string
		revokedAt sql.NullString
	)
	if err := r.db.QueryRowContext(ctx, `
		SELECT s.id, s.admin_user_id, u.username, s.csrf_token, s.expires_at, s.revoked_at
		FROM admin_sessions s
		JOIN admin_users u ON u.id = s.admin_user_id
		WHERE s.token_hash = ?`, tokenHash[:]).Scan(
		&sessionID, &userID, &username, &csrfToken, &expiresAt, &revokedAt,
	); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return session, ErrInvalidSession
		}
		return session, fmt.Errorf("load admin session: %w", err)
	}
	if revokedAt.Valid {
		return session, ErrInvalidSession
	}
	parsedExpiry, err := time.Parse(time.RFC3339Nano, expiresAt)
	if err != nil {
		return session, fmt.Errorf("parse session expires_at: %w", err)
	}
	if !parsedExpiry.After(time.Now().UTC()) {
		return session, ErrInvalidSession
	}
	return AdminSession{
		ID:        sessionID,
		UserID:    userID,
		Username:  username,
		Token:     token,
		CSRFToken: csrfToken,
		ExpiresAt: parsedExpiry,
	}, nil
}

// Logout revokes the session identified by token.
func (r *AdminRepository) Logout(ctx context.Context, token string) error {
	if err := r.check(ctx); err != nil {
		return err
	}
	token = strings.TrimSpace(token)
	if token == "" {
		return ErrInvalidSession
	}
	now := time.Now().UTC().Format(time.RFC3339Nano)
	tokenHash := hashSessionToken(token)
	result, err := r.db.ExecContext(ctx, `
		UPDATE admin_sessions
		SET revoked_at = ?
		WHERE token_hash = ? AND revoked_at IS NULL`,
		now, tokenHash[:],
	)
	if err != nil {
		return fmt.Errorf("revoke admin session: %w", err)
	}
	rows, err := result.RowsAffected()
	if err != nil {
		return fmt.Errorf("check revoked admin session: %w", err)
	}
	if rows != 1 {
		return ErrInvalidSession
	}
	return nil
}

// ValidateCSRF checks that the provided token matches the authenticated session.
func ValidateCSRF(session AdminSession, provided string) error {
	provided = strings.TrimSpace(provided)
	if provided == "" || session.CSRFToken == "" {
		return ErrInvalidCSRF
	}
	if subtle.ConstantTimeCompare([]byte(provided), []byte(session.CSRFToken)) != 1 {
		return ErrInvalidCSRF
	}
	return nil
}

func (r *AdminRepository) createSession(ctx context.Context, userID, username string) (AdminSession, error) {
	var session AdminSession
	sessionID, err := newDeviceID()
	if err != nil {
		return session, fmt.Errorf("generate session ID: %w", err)
	}
	token, err := newSecret(sessionTokenSize)
	if err != nil {
		return session, fmt.Errorf("generate session token: %w", err)
	}
	csrfToken, err := newSecret(sessionTokenSize)
	if err != nil {
		return session, fmt.Errorf("generate csrf token: %w", err)
	}

	now := time.Now().UTC()
	expiresAt := now.Add(adminSessionValidity)
	tokenHash := hashSessionToken(token)
	if _, err := r.db.ExecContext(ctx, `
		INSERT INTO admin_sessions (id, admin_user_id, token_hash, csrf_token, created_at, expires_at)
		VALUES (?, ?, ?, ?, ?, ?)`,
		sessionID, userID, tokenHash[:], csrfToken,
		now.Format(time.RFC3339Nano), expiresAt.Format(time.RFC3339Nano),
	); err != nil {
		return session, fmt.Errorf("store admin session: %w", err)
	}
	return AdminSession{
		ID:        sessionID,
		UserID:    userID,
		Username:  username,
		Token:     token,
		CSRFToken: csrfToken,
		ExpiresAt: expiresAt,
	}, nil
}

func (r *AdminRepository) check(ctx context.Context) error {
	if r == nil || r.db == nil {
		return errors.New("admin repository has no database")
	}
	if ctx == nil {
		return errors.New("auth context must not be nil")
	}
	return ctx.Err()
}
