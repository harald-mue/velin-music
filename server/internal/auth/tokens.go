package auth

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"strings"
	"time"
)

// Device is a registered client without secret material.
type Device struct {
	ID         string
	Name       string
	CreatedAt  time.Time
	LastUsedAt *time.Time
	RevokedAt  *time.Time
}

// IssuedToken contains a plaintext bearer token returned only once at issuance.
type IssuedToken struct {
	DeviceID string
	Token    string
}

// TokenRepository stores hashed device access tokens.
type TokenRepository struct {
	db *sql.DB
}

// NewTokenRepository creates a repository backed by db.
func NewTokenRepository(db *sql.DB) *TokenRepository {
	return &TokenRepository{db: db}
}

// Issue creates a new device token. The plaintext token is returned only from this call.
func (r *TokenRepository) Issue(ctx context.Context, deviceName string) (IssuedToken, error) {
	var issued IssuedToken
	if err := r.check(ctx); err != nil {
		return issued, err
	}
	deviceName, err := normalizeDeviceName(deviceName)
	if err != nil {
		return issued, err
	}

	deviceID, err := newDeviceID()
	if err != nil {
		return issued, fmt.Errorf("generate device ID: %w", err)
	}
	secret, err := newSecret(deviceTokenSecretSize)
	if err != nil {
		return issued, fmt.Errorf("generate device secret: %w", err)
	}
	hash, err := hashDeviceSecret(secret)
	if err != nil {
		return issued, err
	}

	now := time.Now().UTC().Format(time.RFC3339Nano)
	if _, err := r.db.ExecContext(ctx, `
		INSERT INTO access_tokens (id, device_name, token_hash, created_at)
		VALUES (?, ?, ?, ?)`, deviceID, deviceName, hash, now); err != nil {
		return issued, fmt.Errorf("store access token: %w", err)
	}
	return IssuedToken{DeviceID: deviceID, Token: composeDeviceToken(deviceID, secret)}, nil
}

// Authenticate validates a bearer token and returns the device ID.
func (r *TokenRepository) Authenticate(ctx context.Context, token string) (string, error) {
	if err := r.check(ctx); err != nil {
		return "", err
	}
	deviceID, secret, err := parseDeviceToken(strings.TrimSpace(token))
	if err != nil {
		return "", err
	}

	var hash []byte
	var revokedAt sql.NullString
	if err := r.db.QueryRowContext(ctx, `
		SELECT token_hash, revoked_at
		FROM access_tokens
		WHERE id = ?`, deviceID).Scan(&hash, &revokedAt); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return "", ErrInvalidToken
		}
		return "", fmt.Errorf("load access token: %w", err)
	}
	if revokedAt.Valid {
		return "", ErrInvalidToken
	}
	if !verifyDeviceSecret(hash, secret) {
		return "", ErrInvalidToken
	}
	if err := r.touchLastUsed(ctx, deviceID); err != nil {
		return "", err
	}
	return deviceID, nil
}

// Revoke marks a device token as revoked.
func (r *TokenRepository) Revoke(ctx context.Context, deviceID string) error {
	if err := r.check(ctx); err != nil {
		return err
	}
	deviceID = strings.TrimSpace(deviceID)
	if deviceID == "" {
		return ErrDeviceNotFound
	}
	now := time.Now().UTC().Format(time.RFC3339Nano)
	result, err := r.db.ExecContext(ctx, `
		UPDATE access_tokens
		SET revoked_at = ?
		WHERE id = ? AND revoked_at IS NULL`, now, deviceID)
	if err != nil {
		return fmt.Errorf("revoke access token: %w", err)
	}
	rows, err := result.RowsAffected()
	if err != nil {
		return fmt.Errorf("check revoked access token: %w", err)
	}
	if rows != 1 {
		return ErrDeviceNotFound
	}
	return nil
}

// ListDevices returns registered devices in stable creation order.
func (r *TokenRepository) ListDevices(ctx context.Context) ([]Device, error) {
	if err := r.check(ctx); err != nil {
		return nil, err
	}
	rows, err := r.db.QueryContext(ctx, `
		SELECT id, device_name, created_at, last_used_at, revoked_at
		FROM access_tokens
		ORDER BY created_at ASC, id ASC`)
	if err != nil {
		return nil, fmt.Errorf("list devices: %w", err)
	}
	defer rows.Close()

	devices := make([]Device, 0)
	for rows.Next() {
		var device Device
		var createdAt string
		var lastUsedAt sql.NullString
		var revokedAt sql.NullString
		if err := rows.Scan(&device.ID, &device.Name, &createdAt, &lastUsedAt, &revokedAt); err != nil {
			return nil, fmt.Errorf("scan device: %w", err)
		}
		parsed, err := time.Parse(time.RFC3339Nano, createdAt)
		if err != nil {
			return nil, fmt.Errorf("parse device created_at: %w", err)
		}
		device.CreatedAt = parsed
		if lastUsedAt.Valid {
			parsed, err := time.Parse(time.RFC3339Nano, lastUsedAt.String)
			if err != nil {
				return nil, fmt.Errorf("parse device last_used_at: %w", err)
			}
			device.LastUsedAt = &parsed
		}
		if revokedAt.Valid {
			parsed, err := time.Parse(time.RFC3339Nano, revokedAt.String)
			if err != nil {
				return nil, fmt.Errorf("parse device revoked_at: %w", err)
			}
			device.RevokedAt = &parsed
		}
		devices = append(devices, device)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("read devices: %w", err)
	}
	return devices, nil
}

func (r *TokenRepository) touchLastUsed(ctx context.Context, deviceID string) error {
	now := time.Now().UTC().Format(time.RFC3339Nano)
	if _, err := r.db.ExecContext(ctx, `
		UPDATE access_tokens
		SET last_used_at = ?
		WHERE id = ? AND revoked_at IS NULL`, now, deviceID); err != nil {
		return fmt.Errorf("update access token last_used_at: %w", err)
	}
	return nil
}

func (r *TokenRepository) check(ctx context.Context) error {
	if r == nil || r.db == nil {
		return errors.New("token repository has no database")
	}
	if ctx == nil {
		return errors.New("auth context must not be nil")
	}
	return ctx.Err()
}
