package auth

import "errors"

var (
	// ErrInvalidToken indicates that a bearer token is missing, malformed, or not valid.
	ErrInvalidToken = errors.New("invalid access token")
	// ErrInvalidPairingCode indicates that a pairing code is missing, malformed, expired, or already used.
	ErrInvalidPairingCode = errors.New("invalid pairing code")
	// ErrInvalidDeviceName indicates that a device name is empty or too long.
	ErrInvalidDeviceName = errors.New("invalid device name")
	// ErrDeviceNotFound indicates that a device ID does not exist.
	ErrDeviceNotFound = errors.New("device not found")
	// ErrRateLimited indicates that a sensitive endpoint was called too frequently.
	ErrRateLimited = errors.New("rate limit exceeded")
	// ErrInvalidCredentials indicates that an admin username or password is wrong.
	ErrInvalidCredentials = errors.New("invalid credentials")
	// ErrInvalidPassword indicates that a password is empty, too short, or too long.
	ErrInvalidPassword = errors.New("invalid password")
	// ErrInvalidUsername indicates that a username is empty, too long, or invalid.
	ErrInvalidUsername = errors.New("invalid username")
	// ErrInvalidSession indicates that an admin session is missing, expired, or revoked.
	ErrInvalidSession = errors.New("invalid session")
	// ErrInvalidCSRF indicates that a CSRF token is missing or does not match the session.
	ErrInvalidCSRF = errors.New("invalid csrf token")
	// ErrSetupComplete indicates that administrator bootstrap was already performed.
	ErrSetupComplete = errors.New("admin setup already complete")
	// ErrSetupRequired indicates that administrator bootstrap has not been performed.
	ErrSetupRequired = errors.New("admin setup required")
	// ErrInvalidServerURL indicates that a public server URL is missing or malformed.
	ErrInvalidServerURL = errors.New("invalid server url")
)
