package library

import (
	"context"
	"errors"
	"sync"
)

// ScanService starts library scans in the background. It serializes all scan
// work so very large libraries cannot accidentally multiply filesystem,
// metadata-parser, artwork-cache, and SQLite pressure.
type ScanService struct {
	scanner *Scanner
	roots   *Store
	queries *ScanQueryRepository
	mu      sync.Mutex
	active  bool
}

// NewScanService creates a background scan launcher.
func NewScanService(scanner *Scanner, roots *Store, queries *ScanQueryRepository) *ScanService {
	return &ScanService{scanner: scanner, roots: roots, queries: queries}
}

// StartRoot begins a background scan for rootID. The running scan record is
// created before this method returns so status clients cannot miss startup.
func (s *ScanService) StartRoot(rootID string) error {
	if err := s.checkConfigured(); err != nil {
		return err
	}
	ctx := context.Background()
	root, err := s.roots.GetRoot(ctx, rootID)
	if err != nil {
		return err
	}
	if !s.reserve() {
		return ErrScanAlreadyRunning
	}
	scan, err := s.scanner.prepareRootScan(ctx, root)
	if err != nil {
		s.release()
		return err
	}
	go func() {
		defer s.release()
		_, _ = s.scanner.scanPreparedRoot(context.Background(), root, scan)
	}()
	return nil
}

// TryStartAll begins a background full-library scan when no scan is active.
func (s *ScanService) TryStartAll() bool {
	return s.startAll() == nil
}

// StartAll begins a background scan for every configured root.
func (s *ScanService) StartAll() error {
	return s.startAll()
}

func (s *ScanService) startAll() error {
	if err := s.checkConfigured(); err != nil {
		return err
	}
	if !s.reserve() {
		return ErrScanAlreadyRunning
	}

	ctx := context.Background()
	roots, err := s.roots.ListRoots(ctx)
	if err != nil {
		s.release()
		return err
	}
	if len(roots) == 0 {
		s.release()
		return nil
	}
	firstScan, err := s.scanner.prepareRootScan(ctx, roots[0])
	if err != nil {
		s.release()
		return err
	}

	go func() {
		defer s.release()
		_, _ = s.scanner.scanRoots(context.Background(), roots, firstScan)
	}()
	return nil
}

func (s *ScanService) checkConfigured() error {
	if s == nil || s.scanner == nil || s.roots == nil || s.queries == nil {
		return errors.New("scan service is not configured")
	}
	return nil
}

func (s *ScanService) reserve() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.active {
		return false
	}
	s.active = true
	return true
}

func (s *ScanService) release() {
	s.mu.Lock()
	s.active = false
	s.mu.Unlock()
}
