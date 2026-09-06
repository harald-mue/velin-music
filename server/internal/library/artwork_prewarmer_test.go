package library

import (
	"context"
	"errors"
	"sync/atomic"
	"testing"
	"time"
)

func TestArtworkPrewarmerCoalescesTriggers(t *testing.T) {
	var passes atomic.Int32
	firstEnsure := make(chan struct{})
	releaseFirst := make(chan struct{})
	secondPass := make(chan struct{})

	prewarmer := newArtworkPrewarmer(
		func(context.Context) ([]string, error) {
			pass := passes.Add(1)
			if pass == 2 {
				close(secondPass)
			}
			return []string{"cover"}, nil
		},
		func(ctx context.Context, _ string, _ int) error {
			select {
			case <-firstEnsure:
				return nil
			default:
				close(firstEnsure)
				select {
				case <-releaseFirst:
					return nil
				case <-ctx.Done():
					return ctx.Err()
				}
			}
		},
		0,
	)
	t.Cleanup(prewarmer.Stop)

	prewarmer.Trigger()
	waitForSignal(t, firstEnsure, "first prewarm pass")
	for range 20 {
		prewarmer.Trigger()
	}
	close(releaseFirst)
	waitForSignal(t, secondPass, "coalesced prewarm pass")
	prewarmer.Stop()

	if got := passes.Load(); got != 2 {
		t.Fatalf("prewarm passes = %d, want 2", got)
	}
}

func TestArtworkPrewarmerStopCancelsAndWaits(t *testing.T) {
	ensureStarted := make(chan struct{})
	ensureCanceled := make(chan struct{})
	prewarmer := newArtworkPrewarmer(
		func(context.Context) ([]string, error) { return []string{"cover"}, nil },
		func(ctx context.Context, _ string, _ int) error {
			close(ensureStarted)
			<-ctx.Done()
			close(ensureCanceled)
			return ctx.Err()
		},
		0,
	)
	prewarmer.Trigger()
	waitForSignal(t, ensureStarted, "variant ensure")

	prewarmer.Stop()
	select {
	case <-ensureCanceled:
	default:
		t.Fatal("Stop() returned before active ensure observed cancellation")
	}
	prewarmer.Stop()
}

func TestArtworkPrewarmerContinuesAfterItemError(t *testing.T) {
	var calls atomic.Int32
	passDone := make(chan struct{})
	prewarmer := newArtworkPrewarmer(
		func(context.Context) ([]string, error) { return []string{"first", "second"}, nil },
		func(context.Context, string, int) error {
			if calls.Add(1) == 4 {
				close(passDone)
			}
			return errors.New("test failure")
		},
		0,
	)
	t.Cleanup(prewarmer.Stop)

	prewarmer.Trigger()
	waitForSignal(t, passDone, "prewarm pass")
	if got := calls.Load(); got != 4 {
		t.Fatalf("ensure calls = %d, want 4", got)
	}
}

func TestReferencedArtworkCoverIDsExcludesUnreferencedCovers(t *testing.T) {
	database := openLibraryTestDB(t)
	cache, err := NewArtworkCache(t.TempDir())
	if err != nil {
		t.Fatalf("NewArtworkCache() error = %v", err)
	}
	store := NewCoverStore(database, cache)
	referenced, err := store.Store(context.Background(), testPNGImage(2, 2), "image/png")
	if err != nil {
		t.Fatalf("store referenced cover: %v", err)
	}
	unreferenced, err := store.Store(context.Background(), testPNGImage(3, 2), "image/png")
	if err != nil {
		t.Fatalf("store unreferenced cover: %v", err)
	}
	if _, err := database.Exec(
		"INSERT INTO albums (id, title, cover_id) VALUES ('album', 'Album', ?)",
		referenced.ID,
	); err != nil {
		t.Fatalf("insert album: %v", err)
	}

	coverIDs, err := referencedArtworkCoverIDs(context.Background(), database)
	if err != nil {
		t.Fatalf("referencedArtworkCoverIDs() error = %v", err)
	}
	if len(coverIDs) != 1 || coverIDs[0] != referenced.ID {
		t.Fatalf("cover IDs = %v, want [%s] (excluding %s)", coverIDs, referenced.ID, unreferenced.ID)
	}
}

func waitForSignal(t *testing.T, signal <-chan struct{}, operation string) {
	t.Helper()
	select {
	case <-signal:
	case <-time.After(2 * time.Second):
		t.Fatalf("timed out waiting for %s", operation)
	}
}
