package config

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestLoadDefaults(t *testing.T) {
	t.Setenv("VELIN_HTTP_ADDR", "")
	t.Setenv("VELIN_VERSION", "")
	t.Setenv("VELIN_DATA_DIR", "")

	cfg, err := Load()
	if err != nil {
		t.Fatalf("Load() error = %v", err)
	}
	if cfg.HTTPAddr != ":8080" {
		t.Fatalf("HTTPAddr = %q, want %q", cfg.HTTPAddr, ":8080")
	}
	if cfg.Version != "dev" {
		t.Fatalf("Version = %q, want %q", cfg.Version, "dev")
	}
	if !filepath.IsAbs(cfg.DataDir) {
		t.Fatalf("DataDir = %q, want absolute path", cfg.DataDir)
	}
}

func TestLoadConfiguredValues(t *testing.T) {
	t.Setenv("VELIN_HTTP_ADDR", "127.0.0.1:9090")
	t.Setenv("VELIN_VERSION", "test")
	t.Setenv("VELIN_DATA_DIR", "./test-data")

	cfg, err := Load()
	if err != nil {
		t.Fatalf("Load() error = %v", err)
	}
	if cfg.HTTPAddr != "127.0.0.1:9090" || cfg.Version != "test" {
		t.Fatalf("configured values = %+v", cfg)
	}
	if !filepath.IsAbs(cfg.DataDir) || !strings.HasSuffix(cfg.DataDir, filepath.Join("test-data")) {
		t.Fatalf("DataDir = %q", cfg.DataDir)
	}
}

func TestLoadScanSettings(t *testing.T) {
	t.Setenv("VELIN_SCAN_ON_STARTUP", "true")
	t.Setenv("VELIN_SCAN_INTERVAL", "15m")

	cfg, err := Load()
	if err != nil {
		t.Fatalf("Load() error = %v", err)
	}
	if !cfg.ScanOnStartup {
		t.Fatal("ScanOnStartup = false, want true")
	}
	if cfg.ScanInterval != 15*time.Minute {
		t.Fatalf("ScanInterval = %v, want 15m", cfg.ScanInterval)
	}
}

func TestLoadRejectsInvalidScanInterval(t *testing.T) {
	t.Setenv("VELIN_SCAN_INTERVAL", "not-a-duration")
	if _, err := Load(); err == nil {
		t.Fatal("Load() error = nil, want error")
	}

	t.Setenv("VELIN_SCAN_INTERVAL", "-1h")
	if _, err := Load(); err == nil {
		t.Fatal("Load() error = nil for negative interval, want error")
	}
}

func TestLoadRejectsInvalidValues(t *testing.T) {
	for _, test := range []struct {
		name, value string
	}{
		{"VELIN_DATA_DIR", "   "},
		{"VELIN_HTTP_ADDR", "   "},
		{"VELIN_VERSION", "   "},
		{"VELIN_VERSION", strings.Repeat("v", 129)},
	} {
		t.Run(test.name+test.value[:1], func(t *testing.T) {
			t.Setenv(test.name, test.value)
			if _, err := Load(); err == nil {
				t.Fatalf("Load() error = nil for %s", test.name)
			}
		})
	}
}

func TestEnsureDataDir(t *testing.T) {
	path := filepath.Join(t.TempDir(), "velin-data")
	if err := EnsureDataDir(path); err != nil {
		t.Fatalf("EnsureDataDir() error = %v", err)
	}

	info, err := os.Stat(path)
	if err != nil {
		t.Fatalf("stat data directory: %v", err)
	}
	if !info.IsDir() {
		t.Fatal("data path is not a directory")
	}
	if got := info.Mode().Perm(); got != 0o700 {
		t.Fatalf("data directory permissions = %o, want 700", got)
	}
}

func TestEnsureDataDirTightensExistingPermissions(t *testing.T) {
	path := filepath.Join(t.TempDir(), "velin-data")
	if err := os.Mkdir(path, 0o755); err != nil {
		t.Fatalf("create data directory: %v", err)
	}
	if err := EnsureDataDir(path); err != nil {
		t.Fatalf("EnsureDataDir() error = %v", err)
	}
	info, err := os.Stat(path)
	if err != nil {
		t.Fatalf("stat data directory: %v", err)
	}
	if got := info.Mode().Perm(); got != 0o700 {
		t.Fatalf("data directory permissions = %o, want 700", got)
	}
}

func TestEnsureDataDirRejectsFileAndSymlink(t *testing.T) {
	parent := t.TempDir()
	path := filepath.Join(parent, "not-a-directory")
	if err := os.WriteFile(path, []byte("x"), 0o600); err != nil {
		t.Fatalf("create file: %v", err)
	}
	if err := EnsureDataDir(path); err == nil {
		t.Fatal("EnsureDataDir(file) error = nil, want error")
	}

	target := filepath.Join(parent, "target")
	if err := os.Mkdir(target, 0o700); err != nil {
		t.Fatalf("create symlink target: %v", err)
	}
	link := filepath.Join(parent, "link")
	if err := os.Symlink(target, link); err != nil {
		t.Skipf("symlinks unavailable: %v", err)
	}
	if err := EnsureDataDir(link); err == nil {
		t.Fatal("EnsureDataDir(symlink) error = nil, want error")
	}
}
