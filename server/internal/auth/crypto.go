package auth

import (
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"

	"golang.org/x/crypto/argon2"
	"golang.org/x/crypto/bcrypt"
)

const (
	deviceTokenSecretSize = 32
	pairingCodeSecretSize = 20
	sessionTokenSize      = 32
	deviceIDHexLength     = 32
	bcryptCost            = 12
	maxDeviceNameRunes    = 128
	minAdminPasswordBytes = 8
	maxAdminPasswordBytes = 1024
	maxAdminUsernameRunes = 64
	adminSessionValidity  = 24 * time.Hour
)

func newDeviceID() (string, error) {
	var raw [16]byte
	if _, err := rand.Read(raw[:]); err != nil {
		return "", err
	}
	return hex.EncodeToString(raw[:]), nil
}

func newSecret(size int) (string, error) {
	raw := make([]byte, size)
	if _, err := rand.Read(raw); err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(raw), nil
}

func hashDeviceSecret(secret string) ([]byte, error) {
	hash, err := bcrypt.GenerateFromPassword([]byte(secret), bcryptCost)
	if err != nil {
		return nil, fmt.Errorf("hash device secret: %w", err)
	}
	return hash, nil
}

func verifyDeviceSecret(hash []byte, secret string) bool {
	return bcrypt.CompareHashAndPassword(hash, []byte(secret)) == nil
}

func hashPairingCode(code string) [sha256.Size]byte {
	return sha256.Sum256([]byte(code))
}

func hashSessionToken(token string) [sha256.Size]byte {
	return sha256.Sum256([]byte(token))
}

func hashAdminPassword(password string) (string, error) {
	salt := make([]byte, 16)
	if _, err := rand.Read(salt); err != nil {
		return "", err
	}
	hash := argon2.IDKey([]byte(password), salt, 3, 64*1024, 2, 32)
	return fmt.Sprintf("$argon2id$v=19$m=65536,t=3,p=2$%s$%s",
		base64.RawStdEncoding.EncodeToString(salt),
		base64.RawStdEncoding.EncodeToString(hash),
	), nil
}

func verifyAdminPassword(encoded, password string) bool {
	parts := strings.Split(encoded, "$")
	if len(parts) != 6 || parts[1] != "argon2id" || parts[2] != "v=19" || parts[3] != "m=65536,t=3,p=2" {
		return false
	}
	salt, err := base64.RawStdEncoding.DecodeString(parts[4])
	if err != nil {
		return false
	}
	expected, err := base64.RawStdEncoding.DecodeString(parts[5])
	if err != nil {
		return false
	}
	actual := argon2.IDKey([]byte(password), salt, 3, 64*1024, 2, uint32(len(expected)))
	return subtle.ConstantTimeCompare(actual, expected) == 1
}

func normalizeUsername(value string) (string, error) {
	value = strings.TrimSpace(value)
	if value == "" {
		return "", ErrInvalidUsername
	}
	if !utf8.ValidString(value) {
		return "", ErrInvalidUsername
	}
	if len([]rune(value)) > maxAdminUsernameRunes {
		return "", ErrInvalidUsername
	}
	for _, char := range value {
		if unicode.IsControl(char) {
			return "", ErrInvalidUsername
		}
	}
	return value, nil
}

func normalizePassword(value string) (string, error) {
	if !utf8.ValidString(value) {
		return "", ErrInvalidPassword
	}
	if len(value) < minAdminPasswordBytes {
		return "", ErrInvalidPassword
	}
	if len(value) > maxAdminPasswordBytes {
		return "", ErrInvalidPassword
	}
	return value, nil
}

// NormalizeServerURL validates a public HTTP(S) server URL for QR payloads.
func NormalizeServerURL(value string) (string, error) {
	value = strings.TrimSpace(value)
	if value == "" {
		return "", ErrInvalidServerURL
	}
	if len(value) > 2048 {
		return "", ErrInvalidServerURL
	}
	if !strings.HasPrefix(value, "http://") && !strings.HasPrefix(value, "https://") {
		return "", ErrInvalidServerURL
	}
	if strings.Contains(value, "@") {
		return "", ErrInvalidServerURL
	}
	return strings.TrimRight(value, "/"), nil
}

func composeDeviceToken(deviceID, secret string) string {
	return deviceID + "." + secret
}

func parseDeviceToken(token string) (deviceID, secret string, err error) {
	dot := strings.IndexByte(token, '.')
	if dot <= 0 || dot >= len(token)-1 {
		return "", "", ErrInvalidToken
	}
	deviceID = token[:dot]
	secret = token[dot+1:]
	if len(deviceID) != deviceIDHexLength {
		return "", "", ErrInvalidToken
	}
	if _, err := hex.DecodeString(deviceID); err != nil {
		return "", "", ErrInvalidToken
	}
	if secret == "" {
		return "", "", ErrInvalidToken
	}
	return deviceID, secret, nil
}

func normalizeDeviceName(value string) (string, error) {
	value = strings.TrimSpace(value)
	if value == "" {
		return "", ErrInvalidDeviceName
	}
	if !utf8.ValidString(value) {
		return "", ErrInvalidDeviceName
	}
	if len([]rune(value)) > maxDeviceNameRunes {
		return "", ErrInvalidDeviceName
	}
	for _, char := range value {
		if unicode.IsControl(char) {
			return "", ErrInvalidDeviceName
		}
	}
	return value, nil
}

func parseBearerToken(header string) (string, error) {
	const prefix = "Bearer "
	if !strings.HasPrefix(header, prefix) {
		return "", ErrInvalidToken
	}
	token := strings.TrimSpace(header[len(prefix):])
	if token == "" {
		return "", ErrInvalidToken
	}
	return token, nil
}

func rejectUnexpectedPairingError(err error) error {
	if err == nil {
		return nil
	}
	if errors.Is(err, ErrInvalidPairingCode) {
		return err
	}
	return fmt.Errorf("pairing exchange: %w", err)
}
