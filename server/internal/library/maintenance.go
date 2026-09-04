package library

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"time"
)

// StartupMaintenanceResult reports startup recovery and cache cleanup work.
type StartupMaintenanceResult struct {
	RecoveredScans    int
	RemovedCacheFiles int
}

// RunStartupMaintenance recovers abandoned running scans and garbage-collects
// unreferenced artwork-cache files.
func RunStartupMaintenance(ctx context.Context, db *sql.DB, cache *ArtworkCache) (StartupMaintenanceResult, error) {
	var result StartupMaintenanceResult
	recovered, err := RecoverInterruptedScans(ctx, db)
	if err != nil {
		return result, err
	}
	result.RecoveredScans = recovered
	if cache == nil {
		return result, nil
	}
	removed, err := cache.GarbageCollect(ctx, db)
	if err != nil {
		return result, err
	}
	result.RemovedCacheFiles = removed
	return result, nil
}

// RecoverInterruptedScans marks abandoned running scans as failed, records an
// interrupted error, and clears their temporary seen markers.
func RecoverInterruptedScans(ctx context.Context, db *sql.DB) (int, error) {
	if db == nil {
		return 0, errors.New("database is not configured")
	}
	if err := ctx.Err(); err != nil {
		return 0, err
	}

	tx, err := db.BeginTx(ctx, nil)
	if err != nil {
		return 0, fmt.Errorf("begin interrupted scan recovery: %w", err)
	}
	defer func() { _ = tx.Rollback() }()

	rows, err := tx.QueryContext(ctx, "SELECT id, root_id FROM scan_runs WHERE status = 'running'")
	if err != nil {
		return 0, fmt.Errorf("list running scans: %w", err)
	}
	defer rows.Close()

	type runningScan struct {
		id     string
		rootID string
	}
	var scans []runningScan
	for rows.Next() {
		var scan runningScan
		if err := rows.Scan(&scan.id, &scan.rootID); err != nil {
			return 0, fmt.Errorf("scan running scan row: %w", err)
		}
		scans = append(scans, scan)
	}
	if err := rows.Err(); err != nil {
		return 0, fmt.Errorf("iterate running scans: %w", err)
	}

	now := time.Now().UTC().Format(time.RFC3339Nano)
	for _, scan := range scans {
		if _, err := tx.ExecContext(ctx, `
			INSERT INTO scan_errors (scan_run_id, root_id, source_name, error_code, message, created_at)
			VALUES (?, ?, '', 'interrupted', 'scan interrupted by server restart', ?)`, scan.id, scan.rootID, now); err != nil {
			return 0, fmt.Errorf("record interrupted scan error: %w", err)
		}
		result, err := tx.ExecContext(ctx, `
			UPDATE scan_runs
			SET status = 'failed', finished_at = ?
			WHERE id = ? AND status = 'running'`, now, scan.id)
		if err != nil {
			return 0, fmt.Errorf("mark interrupted scan failed: %w", err)
		}
		updated, err := result.RowsAffected()
		if err != nil {
			return 0, fmt.Errorf("check interrupted scan update: %w", err)
		}
		if updated != 1 {
			return 0, errors.New("interrupted scan state changed during recovery")
		}
		if _, err := tx.ExecContext(ctx, "DELETE FROM scan_seen_tracks WHERE scan_run_id = ?", scan.id); err != nil {
			return 0, fmt.Errorf("clear interrupted scan markers: %w", err)
		}
	}
	if err := tx.Commit(); err != nil {
		return 0, fmt.Errorf("commit interrupted scan recovery: %w", err)
	}
	return len(scans), nil
}

// GarbageCollect removes cache files that are no longer referenced by the covers table.
func (c *ArtworkCache) GarbageCollect(ctx context.Context, db *sql.DB) (int, error) {
	if c == nil || c.directory == "" {
		return 0, errors.New("artwork cache is not configured")
	}
	if db == nil {
		return 0, errors.New("database is not configured")
	}
	if err := ctx.Err(); err != nil {
		return 0, err
	}

	referenced, err := referencedCoverCachePaths(ctx, db)
	if err != nil {
		return 0, err
	}

	entries, err := os.ReadDir(c.directory)
	if err != nil {
		return 0, fmt.Errorf("read artwork cache directory: %w", err)
	}

	cacheDir := filepath.Clean(c.directory)
	removed := 0
	for _, entry := range entries {
		if err := ctx.Err(); err != nil {
			return removed, err
		}
		if entry.IsDir() {
			continue
		}
		name := entry.Name()
		if strings.HasPrefix(name, ".cover-") {
			continue
		}
		path := filepath.Join(cacheDir, name)
		cleanPath := filepath.Clean(path)
		relative, relErr := filepath.Rel(cacheDir, cleanPath)
		if relErr != nil || relative == ".." || strings.HasPrefix(relative, ".."+string(os.PathSeparator)) {
			continue
		}
		info, err := entry.Info()
		if err != nil {
			return removed, fmt.Errorf("inspect artwork cache entry: %w", err)
		}
		if info.Mode()&os.ModeSymlink != 0 || !info.Mode().IsRegular() {
			continue
		}
		if _, ok := referenced[cleanPath]; ok {
			continue
		}
		if err := os.Remove(cleanPath); err != nil {
			if errors.Is(err, os.ErrNotExist) {
				continue
			}
			return removed, fmt.Errorf("remove unreferenced artwork cache file: %w", err)
		}
		removed++
	}
	return removed, nil
}

func referencedCoverCachePaths(ctx context.Context, db *sql.DB) (map[string]struct{}, error) {
	rows, err := db.QueryContext(ctx, "SELECT cache_path FROM covers")
	if err != nil {
		return nil, fmt.Errorf("list referenced cover cache paths: %w", err)
	}
	defer rows.Close()

	referenced := make(map[string]struct{})
	for rows.Next() {
		var cachePath string
		if err := rows.Scan(&cachePath); err != nil {
			return nil, fmt.Errorf("scan cover cache path: %w", err)
		}
		referenced[filepath.Clean(cachePath)] = struct{}{}
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("iterate cover cache paths: %w", err)
	}
	return referenced, nil
}
