package auth

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"strings"
	"time"
)

const (
	pairingValidity   = 5 * time.Minute
	PairingRateWindow = time.Minute
)

// PairingCode is a short-lived one-time secret created for QR pairing.
type PairingCode struct {
	ID         string
	DeviceName string
	Code       string
	ExpiresAt  time.Time
}

// PairingRepository stores hashed pairing codes.
type PairingRepository struct {
	db *sql.DB
}

// NewPairingRepository creates a repository backed by db.
func NewPairingRepository(db *sql.DB) *PairingRepository {
	return &PairingRepository{db: db}
}

// Create stores a new pairing code. The plaintext code is returned only from this call.
func (r *PairingRepository) Create(ctx context.Context, deviceName string) (PairingCode, error) {
	var pairing PairingCode
	if err := r.check(ctx); err != nil {
		return pairing, err
	}
	deviceName, err := normalizeDeviceName(deviceName)
	if err != nil {
		return pairing, err
	}

	id, err := newDeviceID()
	if err != nil {
		return pairing, fmt.Errorf("generate pairing ID: %w", err)
	}
	code, err := newSecret(pairingCodeSecretSize)
	if err != nil {
		return pairing, fmt.Errorf("generate pairing code: %w", err)
	}

	now := time.Now().UTC()
	expiresAt := now.Add(pairingValidity)
	codeHash := hashPairingCode(code)
	if _, err := r.db.ExecContext(ctx, `
		INSERT INTO pairing_codes (id, code_hash, device_name, created_at, expires_at)
		VALUES (?, ?, ?, ?, ?)`,
		id, codeHash[:], deviceName,
		now.Format(time.RFC3339Nano), expiresAt.Format(time.RFC3339Nano),
	); err != nil {
		return pairing, fmt.Errorf("store pairing code: %w", err)
	}
	return PairingCode{
		ID:         id,
		DeviceName: deviceName,
		Code:       code,
		ExpiresAt:  expiresAt,
	}, nil
}

// Exchange consumes a pairing code and issues a permanent device token.
func (r *PairingRepository) Exchange(ctx context.Context, code string, tokens *TokenRepository) (IssuedToken, error) {
	var issued IssuedToken
	if err := r.check(ctx); err != nil {
		return issued, err
	}
	if tokens == nil {
		return issued, errors.New("token repository must not be nil")
	}
	code = strings.TrimSpace(code)
	if code == "" {
		return issued, ErrInvalidPairingCode
	}

	tx, err := r.db.BeginTx(ctx, nil)
	if err != nil {
		return issued, fmt.Errorf("begin pairing exchange: %w", err)
	}
	defer func() { _ = tx.Rollback() }()

	provided := hashPairingCode(code)
	var matchID string
	var matchDeviceName string
	err = tx.QueryRowContext(ctx, `
		SELECT id, device_name
		FROM pairing_codes
		WHERE code_hash = ? AND consumed_at IS NULL AND expires_at > ?`,
		provided[:], time.Now().UTC().Format(time.RFC3339Nano),
	).Scan(&matchID, &matchDeviceName)
	if errors.Is(err, sql.ErrNoRows) {
		return issued, ErrInvalidPairingCode
	}
	if err != nil {
		return issued, rejectUnexpectedPairingError(fmt.Errorf("load pairing code: %w", err))
	}

	now := time.Now().UTC().Format(time.RFC3339Nano)
	result, err := tx.ExecContext(ctx, `
		UPDATE pairing_codes
		SET consumed_at = ?
		WHERE id = ? AND consumed_at IS NULL`, now, matchID)
	if err != nil {
		return issued, rejectUnexpectedPairingError(fmt.Errorf("consume pairing code: %w", err))
	}
	updated, err := result.RowsAffected()
	if err != nil {
		return issued, rejectUnexpectedPairingError(fmt.Errorf("check consumed pairing code: %w", err))
	}
	if updated != 1 {
		return issued, ErrInvalidPairingCode
	}
	if err := tx.Commit(); err != nil {
		return issued, rejectUnexpectedPairingError(fmt.Errorf("commit pairing exchange: %w", err))
	}
	return tokens.Issue(ctx, matchDeviceName)
}

func (r *PairingRepository) check(ctx context.Context) error {
	if r == nil || r.db == nil {
		return errors.New("pairing repository has no database")
	}
	if ctx == nil {
		return errors.New("auth context must not be nil")
	}
	return ctx.Err()
}
