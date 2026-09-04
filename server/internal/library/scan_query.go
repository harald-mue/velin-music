package library

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"strings"
	"time"
)

// ScanRunRecord is one persisted scan run for administration and monitoring.
type ScanRunRecord struct {
	ID           string
	RootID       string
	Status       string
	StartedAt    time.Time
	FinishedAt   *time.Time
	FilesSeen    int
	FilesIndexed int
	FilesRemoved int
}

// ScanQueryRepository reads scan history from SQLite.
type ScanQueryRepository struct {
	db *sql.DB
}

// NewScanQueryRepository creates a scan-history reader backed by db.
func NewScanQueryRepository(db *sql.DB) *ScanQueryRepository {
	return &ScanQueryRepository{db: db}
}

// HasRunningScan reports whether rootID currently has a running scan.
func (r *ScanQueryRepository) HasRunningScan(ctx context.Context, rootID string) (bool, error) {
	if r == nil || r.db == nil {
		return false, errors.New("scan query repository has no database")
	}
	var running bool
	if err := r.db.QueryRowContext(ctx, "SELECT EXISTS(SELECT 1 FROM scan_runs WHERE root_id = ? AND status = 'running')", rootID).Scan(&running); err != nil {
		return false, fmt.Errorf("check running scan: %w", err)
	}
	return running, nil
}

// ListRecent returns the newest scan runs up to limit.
func (r *ScanQueryRepository) ListRecent(ctx context.Context, limit int) ([]ScanRunRecord, error) {
	if r == nil || r.db == nil {
		return nil, errors.New("scan query repository has no database")
	}
	if limit <= 0 {
		limit = 20
	}
	if limit > 200 {
		limit = 200
	}

	rows, err := r.db.QueryContext(ctx, `
		SELECT id, root_id, status, started_at, finished_at, files_seen, files_indexed, files_removed
		FROM scan_runs
		ORDER BY started_at DESC
		LIMIT ?`, limit)
	if err != nil {
		return nil, fmt.Errorf("list recent scans: %w", err)
	}
	defer rows.Close()

	var runs []ScanRunRecord
	for rows.Next() {
		record, err := scanRunRecord(rows.Scan)
		if err != nil {
			return nil, err
		}
		runs = append(runs, record)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("iterate recent scans: %w", err)
	}
	return runs, nil
}

// LatestForRoot returns the newest scan run for rootID, or nil when none exist.
func (r *ScanQueryRepository) LatestForRoot(ctx context.Context, rootID string) (*ScanRunRecord, error) {
	if r == nil || r.db == nil {
		return nil, errors.New("scan query repository has no database")
	}

	row := r.db.QueryRowContext(ctx, `
		SELECT id, root_id, status, started_at, finished_at, files_seen, files_indexed, files_removed
		FROM scan_runs
		WHERE root_id = ?
		ORDER BY started_at DESC
		LIMIT 1`, rootID)
	record, err := scanRunRecord(row.Scan)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return &record, nil
}

// ScanErrorRecord is one persisted file or scan-level diagnostic.
type ScanErrorRecord struct {
	ID         int64
	ScanRunID  string
	RootID     string
	SourceName string
	ErrorCode  string
	Message    string
	CreatedAt  time.Time
}

// GetByID returns one scan run by opaque ID.
func (r *ScanQueryRepository) GetByID(ctx context.Context, scanID string) (*ScanRunRecord, error) {
	if r == nil || r.db == nil {
		return nil, errors.New("scan query repository has no database")
	}
	scanID = strings.TrimSpace(scanID)
	if scanID == "" {
		return nil, errors.New("scan run ID must not be empty")
	}

	row := r.db.QueryRowContext(ctx, `
		SELECT id, root_id, status, started_at, finished_at, files_seen, files_indexed, files_removed
		FROM scan_runs
		WHERE id = ?`, scanID)
	record, err := scanRunRecord(row.Scan)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrScanRunNotFound
	}
	if err != nil {
		return nil, err
	}
	return &record, nil
}

// CountErrors returns the number of persisted errors for scanRunID.
func (r *ScanQueryRepository) CountErrors(ctx context.Context, scanRunID string) (int, error) {
	if r == nil || r.db == nil {
		return 0, errors.New("scan query repository has no database")
	}
	var count int
	if err := r.db.QueryRowContext(ctx, `SELECT COUNT(*) FROM scan_errors WHERE scan_run_id = ?`, scanRunID).Scan(&count); err != nil {
		return 0, fmt.Errorf("count scan errors: %w", err)
	}
	return count, nil
}

// ListErrors returns persisted scan errors for scanRunID up to limit.
func (r *ScanQueryRepository) ListErrors(ctx context.Context, scanRunID string, limit int) ([]ScanErrorRecord, error) {
	if r == nil || r.db == nil {
		return nil, errors.New("scan query repository has no database")
	}
	if limit <= 0 {
		limit = 50
	}
	if limit > 200 {
		limit = 200
	}

	rows, err := r.db.QueryContext(ctx, `
		SELECT id, scan_run_id, COALESCE(root_id, ''), COALESCE(source_name, ''), error_code, message, created_at
		FROM scan_errors
		WHERE scan_run_id = ?
		ORDER BY created_at ASC, id ASC
		LIMIT ?`, scanRunID, limit)
	if err != nil {
		return nil, fmt.Errorf("list scan errors: %w", err)
	}
	defer rows.Close()

	var records []ScanErrorRecord
	for rows.Next() {
		var record ScanErrorRecord
		var createdAt string
		if err := rows.Scan(
			&record.ID,
			&record.ScanRunID,
			&record.RootID,
			&record.SourceName,
			&record.ErrorCode,
			&record.Message,
			&createdAt,
		); err != nil {
			return nil, fmt.Errorf("scan scan error row: %w", err)
		}
		parsed, err := time.Parse(time.RFC3339Nano, createdAt)
		if err != nil {
			return nil, fmt.Errorf("parse scan error created_at: %w", err)
		}
		record.CreatedAt = parsed.UTC()
		records = append(records, record)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("iterate scan errors: %w", err)
	}
	return records, nil
}

func scanRunRecord(scan func(dest ...any) error) (ScanRunRecord, error) {
	var record ScanRunRecord
	var startedAt string
	var finishedAt sql.NullString
	if err := scan(
		&record.ID,
		&record.RootID,
		&record.Status,
		&startedAt,
		&finishedAt,
		&record.FilesSeen,
		&record.FilesIndexed,
		&record.FilesRemoved,
	); err != nil {
		return ScanRunRecord{}, err
	}
	parsedStarted, err := time.Parse(time.RFC3339Nano, startedAt)
	if err != nil {
		return ScanRunRecord{}, fmt.Errorf("parse scan started_at: %w", err)
	}
	record.StartedAt = parsedStarted.UTC()
	if finishedAt.Valid {
		parsedFinished, err := time.Parse(time.RFC3339Nano, finishedAt.String)
		if err != nil {
			return ScanRunRecord{}, fmt.Errorf("parse scan finished_at: %w", err)
		}
		value := parsedFinished.UTC()
		record.FinishedAt = &value
	}
	return record, nil
}
