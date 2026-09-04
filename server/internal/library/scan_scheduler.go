package library

import (
	"log/slog"
	"sync"
	"time"
)

// ScanScheduler triggers periodic full-library scans.
type ScanScheduler struct {
	scans    *ScanService
	interval time.Duration
	stopCh   chan struct{}
	doneCh   chan struct{}
	mu       sync.Mutex
	running  bool
	stopOnce sync.Once
}

// NewScanScheduler creates a scheduler for the given interval.
// Intervals less than or equal to zero are treated as disabled.
func NewScanScheduler(scans *ScanService, interval time.Duration) *ScanScheduler {
	return &ScanScheduler{
		scans:    scans,
		interval: interval,
		stopCh:   make(chan struct{}),
		doneCh:   make(chan struct{}),
	}
}

// Start begins periodic scans when the interval is positive.
func (s *ScanScheduler) Start() {
	if s == nil || s.interval <= 0 {
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.running {
		return
	}
	s.running = true
	go s.run()
}

func (s *ScanScheduler) run() {
	defer close(s.doneCh)
	ticker := time.NewTicker(s.interval)
	defer ticker.Stop()
	for {
		select {
		case <-s.stopCh:
			return
		case <-ticker.C:
			if !s.scans.TryStartAll() {
				slog.Info("scheduled scan skipped", "reason", "scan_already_running")
			}
		}
	}
}

// Stop waits for the scheduler goroutine to exit.
func (s *ScanScheduler) Stop() {
	if s == nil || s.interval <= 0 {
		return
	}
	s.mu.Lock()
	if !s.running {
		s.mu.Unlock()
		return
	}
	s.mu.Unlock()
	s.stopOnce.Do(func() { close(s.stopCh) })
	<-s.doneCh
}
