package httpapi

import (
	"context"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"testing"
	"time"

	"github.com/harald-mue/velin-music/server/internal/db"
)

func TestNewRecoversInterruptedScansAtStartup(t *testing.T) {
	database, err := db.Open(context.Background(), filepath.Join(t.TempDir(), "velin.db"))
	if err != nil {
		t.Fatalf("open database: %v", err)
	}
	t.Cleanup(func() { _ = database.Close() })

	rootDir := t.TempDir()
	now := time.Now().UTC().Format(time.RFC3339Nano)
	if _, err := database.Exec(`
		INSERT INTO library_roots (id, path, created_at, updated_at)
		VALUES ('root-1', ?, ?, ?)`, rootDir, now, now); err != nil {
		t.Fatalf("insert root: %v", err)
	}
	if _, err := database.Exec(`
		INSERT INTO scan_runs (id, root_id, status, started_at)
		VALUES ('scan-running', 'root-1', 'running', ?)`, now); err != nil {
		t.Fatalf("insert running scan: %v", err)
	}

	api, err := New(Config{Version: "test", DataDir: t.TempDir()}, database)
	if err != nil {
		t.Fatalf("New() error = %v", err)
	}
	t.Cleanup(api.Stop)

	var status string
	if err := database.QueryRow("SELECT status FROM scan_runs WHERE id = 'scan-running'").Scan(&status); err != nil {
		t.Fatalf("read scan status: %v", err)
	}
	if status != "failed" {
		t.Fatalf("scan status = %q, want failed", status)
	}

	statusReq := httptest.NewRequest(http.MethodGet, "/api/v1/status", nil)
	statusRes := httptest.NewRecorder()
	api.Handler().ServeHTTP(statusRes, statusReq)
	if statusRes.Code != http.StatusOK {
		t.Fatalf("status code = %d", statusRes.Code)
	}
}
