package config

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"time"
)

// Config contains process-level settings for the Velin server.
type Config struct {
	HTTPAddr      string
	Version       string
	DataDir       string
	PublicURL     string
	SecureCookies bool
	ScanOnStartup bool
	ScanInterval  time.Duration
}

// Load reads configuration from the environment and applies safe development defaults.
func Load() (Config, error) {
	httpAddr := strings.TrimSpace(envOrDefault("VELIN_HTTP_ADDR", ":8080"))
	if httpAddr == "" || len(httpAddr) > 512 {
		return Config{}, errors.New("VELIN_HTTP_ADDR must contain between 1 and 512 bytes")
	}
	version := strings.TrimSpace(envOrDefault("VELIN_VERSION", "dev"))
	if version == "" || len(version) > 128 {
		return Config{}, errors.New("VELIN_VERSION must contain between 1 and 128 bytes")
	}
	dataDir := strings.TrimSpace(envOrDefault("VELIN_DATA_DIR", "./data"))
	if dataDir == "" {
		return Config{}, errors.New("VELIN_DATA_DIR must not be empty")
	}

	dataDir, err := filepath.Abs(dataDir)
	if err != nil {
		return Config{}, fmt.Errorf("resolve data directory: %w", err)
	}

	publicURL := strings.TrimSpace(os.Getenv("VELIN_PUBLIC_URL"))
	if publicURL != "" {
		if len(publicURL) > 2048 {
			return Config{}, errors.New("VELIN_PUBLIC_URL must contain at most 2048 bytes")
		}
		publicURL = strings.TrimRight(publicURL, "/")
	}

	secureCookies := false
	switch strings.ToLower(strings.TrimSpace(os.Getenv("VELIN_SECURE_COOKIES"))) {
	case "1", "true", "yes", "on":
		secureCookies = true
	}

	scanOnStartup := false
	switch strings.ToLower(strings.TrimSpace(os.Getenv("VELIN_SCAN_ON_STARTUP"))) {
	case "1", "true", "yes", "on":
		scanOnStartup = true
	}

	var scanInterval time.Duration
	if raw := strings.TrimSpace(os.Getenv("VELIN_SCAN_INTERVAL")); raw != "" {
		parsed, err := time.ParseDuration(raw)
		if err != nil {
			return Config{}, fmt.Errorf("VELIN_SCAN_INTERVAL: %w", err)
		}
		if parsed < 0 {
			return Config{}, errors.New("VELIN_SCAN_INTERVAL must not be negative")
		}
		scanInterval = parsed
	}

	return Config{
		HTTPAddr:      httpAddr,
		Version:       version,
		DataDir:       dataDir,
		PublicURL:     publicURL,
		SecureCookies: secureCookies,
		ScanOnStartup: scanOnStartup,
		ScanInterval:  scanInterval,
	}, nil
}

// EnsureDataDir creates the data directory if needed and rejects insecure or invalid paths.
func EnsureDataDir(path string) error {
	if strings.TrimSpace(path) == "" {
		return fmt.Errorf("data directory must not be empty")
	}

	if err := os.MkdirAll(path, 0o700); err != nil {
		return fmt.Errorf("create data directory: %w", err)
	}

	info, err := os.Lstat(path)
	if err != nil {
		return fmt.Errorf("inspect data directory: %w", err)
	}
	if info.Mode()&os.ModeSymlink != 0 {
		return errors.New("data directory must not be a symlink")
	}
	if !info.IsDir() {
		return errors.New("data directory is not a directory")
	}
	if info.Mode().Perm()&0o077 != 0 {
		return errors.New("data directory is accessible by other users")
	}
	return nil
}

func envOrDefault(name, fallback string) string {
	if value := os.Getenv(name); value != "" {
		return value
	}
	return fallback
}
