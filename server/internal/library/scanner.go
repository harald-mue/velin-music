package library

import (
	"context"
	"errors"
	"fmt"
	"path/filepath"
	"strings"
	"time"
)

// Scanner connects discovery, metadata parsing, artwork caching, and track
// persistence for one or more configured roots.
type Scanner struct {
	roots  *Store
	tracks *TrackRepository
}

// ScanResult reports one completed or failed root scan.
type ScanResult struct {
	RootID       string
	FilesSeen    int
	FilesIndexed int
	Errors       int
	Completed    bool
}

// NewScanner creates an automatic scan orchestrator.
func NewScanner(roots *Store, tracks *TrackRepository) *Scanner {
	return &Scanner{roots: roots, tracks: tracks}
}

// ScanAll scans configured roots sequentially and continues after independent
// root failures. Cancellation stops further work. Sequential orchestration
// keeps database and filesystem pressure bounded; per-root parser concurrency
// can be introduced later based on measurements.
func (s *Scanner) ScanAll(ctx context.Context) ([]ScanResult, error) {
	if s == nil || s.roots == nil || s.tracks == nil {
		return nil, errors.New("scanner is not configured")
	}
	roots, err := s.roots.ListRoots(ctx)
	if err != nil {
		return nil, err
	}
	return s.scanRoots(ctx, roots, nil)
}

func (s *Scanner) scanRoots(ctx context.Context, roots []Root, firstScan *Scan) ([]ScanResult, error) {
	results := make([]ScanResult, 0, len(roots))
	var scanErrors []error
	for index, root := range roots {
		if err := ctx.Err(); err != nil {
			return results, err
		}
		var result ScanResult
		var err error
		if index == 0 && firstScan != nil {
			result, err = s.scanPreparedRoot(ctx, root, firstScan)
		} else {
			result, err = s.ScanRoot(ctx, root)
		}
		results = append(results, result)
		if err == nil {
			continue
		}
		if errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) {
			return results, err
		}
		scanErrors = append(scanErrors, fmt.Errorf("scan root %s: %w", root.ID, err))
	}
	return results, errors.Join(scanErrors...)
}

// ScanRoot performs one complete root scan. A parse/index error for an
// individual file is recorded and retained as seen; it does not cause valid
// tracks to be deleted. Discovery or database failures fail the whole scan.
func (s *Scanner) ScanRoot(ctx context.Context, root Root) (ScanResult, error) {
	result := ScanResult{RootID: root.ID}
	scan, err := s.prepareRootScan(ctx, root)
	if err != nil {
		return result, err
	}
	return s.scanPreparedRoot(ctx, root, scan)
}

func (s *Scanner) prepareRootScan(ctx context.Context, root Root) (*Scan, error) {
	if s == nil || s.tracks == nil {
		return nil, errors.New("scanner is not configured")
	}
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	return s.tracks.BeginScan(ctx, root.ID)
}

func (s *Scanner) scanPreparedRoot(ctx context.Context, root Root, scan *Scan) (ScanResult, error) {
	result := ScanResult{RootID: root.ID}
	if scan == nil {
		return result, errors.New("scan is not prepared")
	}

	const progressBatchSize = 100
	const progressInterval = time.Second
	lastReportedSeen := 0
	lastReportAt := time.Now()
	reportProgress := func(force bool) error {
		if !force && result.FilesSeen-lastReportedSeen < progressBatchSize && time.Since(lastReportAt) < progressInterval {
			return nil
		}
		if err := scan.ReportProgress(ctx, result.FilesSeen, result.FilesIndexed); err != nil {
			return err
		}
		lastReportedSeen = result.FilesSeen
		lastReportAt = time.Now()
		return nil
	}
	fail := func(cause error, source, code, message string) error {
		persistContext, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		if progressErr := scan.ReportProgress(persistContext, result.FilesSeen, result.FilesIndexed); progressErr != nil {
			cause = fmt.Errorf("record scan progress: %w", progressErr)
		}
		if source != "" || code != "" {
			if recordErr := scan.RecordError(persistContext, source, code, message); recordErr != nil {
				cause = fmt.Errorf("record scan failure: %w", recordErr)
			}
		}
		if failErr := scan.Fail(persistContext, cause); failErr != nil {
			return failErr
		}
		return cause
	}

	err := Discover(ctx, root, func(media MediaFile) error {
		result.FilesSeen++
		unchanged, err := scan.RetainUnchanged(ctx, media)
		if err != nil {
			return err
		}
		if unchanged {
			result.FilesIndexed++
			return reportProgress(false)
		}
		metadata, parseErr := ParseMetadata(ctx, root, media)
		if parseErr != nil {
			if errors.Is(parseErr, context.Canceled) || errors.Is(parseErr, context.DeadlineExceeded) {
				return parseErr
			}
			result.Errors++
			if err := scan.RecordError(ctx, media.RelativePath, "metadata", safeScanMessage(parseErr, root)); err != nil {
				return err
			}
			if err := scan.MarkSeen(ctx, media.RelativePath); err != nil {
				return err
			}
			return reportProgress(false)
		}
		if upsertErr := scan.Upsert(ctx, media, metadata); upsertErr != nil {
			if errors.Is(upsertErr, context.Canceled) || errors.Is(upsertErr, context.DeadlineExceeded) {
				return upsertErr
			}
			result.Errors++
			if err := scan.RecordError(ctx, media.RelativePath, "index", safeScanMessage(upsertErr, root)); err != nil {
				return err
			}
			if err := scan.MarkSeen(ctx, media.RelativePath); err != nil {
				return err
			}
			return reportProgress(false)
		}
		result.FilesIndexed++
		return reportProgress(false)
	})
	if err != nil {
		result.Completed = false
		if errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) {
			return result, fail(err, "", "", "")
		}
		return result, fail(err, "", "discovery", "library discovery failed")
	}
	if err := reportProgress(true); err != nil {
		result.Completed = false
		return result, fail(err, "", "progress", "scan progress could not be recorded")
	}
	if err := scan.Finish(ctx); err != nil {
		result.Completed = false
		return result, fail(err, "", "reconciliation", "scan reconciliation failed")
	}
	result.Completed = true
	return result, nil
}

func safeScanMessage(err error, root Root) string {
	if err == nil {
		return "scan error"
	}
	message := err.Error()
	if root.Path != "" {
		message = strings.ReplaceAll(message, root.Path, "[library root]")
		message = strings.ReplaceAll(message, filepath.Clean(root.Path), "[library root]")
	}
	if message == "" {
		return "scan error"
	}
	return message
}
