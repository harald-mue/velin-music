package library

import (
	"context"
	"errors"
	"sync"
)

// ScanService starts library scans in the background.
type ScanService struct {
	scanner *Scanner
	roots   *Store
	queries *ScanQueryRepository
	mu      sync.Mutex
	allScan bool
}

// NewScanService creates a background scan launcher.
func NewScanService(scanner *Scanner, roots *Store, queries *ScanQueryRepository) *ScanService {
	return &ScanService{scanner: scanner, roots: roots, queries: queries}
}

// StartRoot begins a background scan for rootID.
func (s *ScanService) StartRoot(rootID string) error {
	if s == nil || s.scanner == nil || s.roots == nil || s.queries == nil {
		return errors.New("scan service is not configured")
	}
	ctx := context.Background()
	root, err := s.roots.GetRoot(ctx, rootID)
	if err != nil {
		return err
	}
	running, err := s.queries.HasRunningScan(ctx, rootID)
	if err != nil {
		return err
	}
	if running {
		return ErrScanAlreadyRunning
	}
	go func() {
		_, _ = s.scanner.ScanRoot(context.Background(), root)
	}()
	return nil
}

// TryStartAll begins a background full-library scan when one is not already running.
func (s *ScanService) TryStartAll() bool {
	if s == nil || s.scanner == nil || s.roots == nil {
		return false
	}
	s.mu.Lock()
	if s.allScan {
		s.mu.Unlock()
		return false
	}
	s.allScan = true
	s.mu.Unlock()

	go func() {
		defer func() {
			s.mu.Lock()
			s.allScan = false
			s.mu.Unlock()
		}()
		_, _ = s.scanner.ScanAll(context.Background())
	}()
	return true
}

// StartAll begins a background scan for every configured root.
func (s *ScanService) StartAll() error {
	if s == nil || s.scanner == nil || s.roots == nil {
		return errors.New("scan service is not configured")
	}
	if !s.TryStartAll() {
		return ErrScanAlreadyRunning
	}
	return nil
}
