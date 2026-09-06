package library

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"log/slog"
	"sync"
	"time"
)

const (
	artworkPrewarmPause = 10 * time.Millisecond
	maxPrewarmCoverIDs  = 1_024
)

var artworkPrewarmSizes = [...]int{256, 512}

// ArtworkPrewarmer coalesces requests to build commonly used cover variants
// in one low-pressure, cancellable background worker.
type ArtworkPrewarmer struct {
	cancel    context.CancelFunc
	trigger   chan struct{}
	done      chan struct{}
	stopOnce  sync.Once
	enumerate func(context.Context) ([]string, error)
	ensure    func(context.Context, string, int) error
	pause     time.Duration
}

// NewArtworkPrewarmer creates and starts an artwork prewarming worker.
func NewArtworkPrewarmer(reader *CoverReader) *ArtworkPrewarmer {
	return newArtworkPrewarmer(
		func(ctx context.Context) ([]string, error) {
			if reader == nil || reader.db == nil {
				return nil, errors.New("cover reader is not configured")
			}
			return referencedArtworkCoverIDs(ctx, reader.db)
		},
		func(ctx context.Context, coverID string, size int) error {
			if reader == nil {
				return errors.New("cover reader is not configured")
			}
			return reader.EnsureVariant(ctx, coverID, size)
		},
		artworkPrewarmPause,
	)
}

func newArtworkPrewarmer(
	enumerate func(context.Context) ([]string, error),
	ensure func(context.Context, string, int) error,
	pause time.Duration,
) *ArtworkPrewarmer {
	ctx, cancel := context.WithCancel(context.Background())
	prewarmer := &ArtworkPrewarmer{
		cancel:    cancel,
		trigger:   make(chan struct{}, 1),
		done:      make(chan struct{}),
		enumerate: enumerate,
		ensure:    ensure,
		pause:     pause,
	}
	go prewarmer.run(ctx)
	return prewarmer
}

// Trigger requests a prewarm pass. Concurrent requests are coalesced.
func (p *ArtworkPrewarmer) Trigger() {
	if p == nil {
		return
	}
	select {
	case p.trigger <- struct{}{}:
	default:
	}
}

// Stop cancels active work and waits for the worker to exit.
func (p *ArtworkPrewarmer) Stop() {
	if p == nil {
		return
	}
	p.stopOnce.Do(p.cancel)
	<-p.done
}

func (p *ArtworkPrewarmer) run(ctx context.Context) {
	defer close(p.done)
	for {
		select {
		case <-ctx.Done():
			return
		case <-p.trigger:
			p.prewarm(ctx)
		}
	}
}

func (p *ArtworkPrewarmer) prewarm(ctx context.Context) {
	coverIDs, err := p.enumerate(ctx)
	if err != nil {
		if ctx.Err() == nil {
			slog.Warn("artwork prewarm enumeration failed")
		}
		return
	}
	for _, coverID := range coverIDs {
		for _, size := range artworkPrewarmSizes {
			if err := p.ensure(ctx, coverID, size); err != nil {
				if ctx.Err() != nil {
					return
				}
				slog.Warn("artwork variant prewarm failed", "cover_id", coverID, "size", size)
			}
			if !prewarmPause(ctx, p.pause) {
				return
			}
		}
	}
}

func prewarmPause(ctx context.Context, pause time.Duration) bool {
	if pause <= 0 {
		return ctx.Err() == nil
	}
	timer := time.NewTimer(pause)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return false
	case <-timer.C:
		return true
	}
}

func referencedArtworkCoverIDs(ctx context.Context, db *sql.DB) ([]string, error) {
	rows, err := db.QueryContext(ctx, `
		SELECT cover_id FROM tracks WHERE cover_id IS NOT NULL
		UNION
		SELECT cover_id FROM albums WHERE cover_id IS NOT NULL
		ORDER BY cover_id
		LIMIT ?`,
		maxPrewarmCoverIDs,
	)
	if err != nil {
		return nil, fmt.Errorf("list artwork prewarm cover IDs: %w", err)
	}
	defer rows.Close()

	var coverIDs []string
	for rows.Next() {
		var coverID string
		if err := rows.Scan(&coverID); err != nil {
			return nil, fmt.Errorf("scan artwork prewarm cover ID: %w", err)
		}
		coverIDs = append(coverIDs, coverID)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("iterate artwork prewarm cover IDs: %w", err)
	}
	return coverIDs, nil
}
