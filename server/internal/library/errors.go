package library

import "errors"

var (
	// ErrRootNotFound is returned when a library root ID does not exist.
	ErrRootNotFound = errors.New("library root not found")
	// ErrRootExists is returned when a library root path is already registered.
	ErrRootExists = errors.New("library root already exists")
	// ErrScanAlreadyRunning is returned when a root already has an active scan.
	ErrScanAlreadyRunning = errors.New("scan already running")
	// ErrScanRunNotFound is returned when a scan run ID does not exist.
	ErrScanRunNotFound = errors.New("scan run not found")
)
