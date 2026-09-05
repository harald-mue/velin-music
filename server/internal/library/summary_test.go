package library

import (
	"context"
	"path/filepath"
	"testing"
)

func TestLibrarySummaryCountsEntitiesAndChangesRevision(t *testing.T) {
	ctx := context.Background()
	database := openLibraryTestDB(t)
	repository := NewQueryRepository(database)

	empty, err := repository.Summary(ctx)
	if err != nil {
		t.Fatalf("Summary(empty) error = %v", err)
	}
	if empty.ArtistCount != 0 || empty.AlbumCount != 0 || empty.TrackCount != 0 || empty.Revision == "" {
		t.Fatalf("empty summary = %+v", empty)
	}

	rootID := addTestRoot(t, database, filepath.Join(t.TempDir(), "music"))
	scan, err := NewTrackRepository(database).BeginScan(ctx, rootID)
	if err != nil {
		t.Fatalf("BeginScan() error = %v", err)
	}
	if err := scan.Upsert(ctx, MediaFile{
		RelativePath: "one.flac",
		Format:       FormatFLAC,
		Size:         100,
	}, TrackMetadata{
		Format:      FormatFLAC,
		Title:       "One",
		Artist:      "Track Artist",
		AlbumArtist: "Album Artist",
		Album:       "Album",
	}); err != nil {
		t.Fatalf("Upsert() error = %v", err)
	}

	populated, err := repository.Summary(ctx)
	if err != nil {
		t.Fatalf("Summary(populated) error = %v", err)
	}
	if populated.ArtistCount != 2 || populated.AlbumCount != 1 || populated.TrackCount != 1 {
		t.Fatalf("populated summary = %+v", populated)
	}
	if populated.Revision == empty.Revision {
		t.Fatalf("revision did not change: %q", populated.Revision)
	}

	repeated, err := repository.Summary(ctx)
	if err != nil {
		t.Fatalf("Summary(repeated) error = %v", err)
	}
	if repeated != populated {
		t.Fatalf("repeated summary = %+v, want %+v", repeated, populated)
	}

	if _, err := database.ExecContext(ctx, "UPDATE tracks SET title = 'Updated' WHERE root_id = ?", rootID); err != nil {
		t.Fatalf("update track: %v", err)
	}
	updated, err := repository.Summary(ctx)
	if err != nil || updated.Revision == populated.Revision || updated.TrackCount != 1 {
		t.Fatalf("updated summary = %+v, err = %v", updated, err)
	}

	if _, err := database.ExecContext(ctx, "DELETE FROM tracks WHERE root_id = ?", rootID); err != nil {
		t.Fatalf("delete track: %v", err)
	}
	deleted, err := repository.Summary(ctx)
	if err != nil {
		t.Fatalf("Summary(deleted) error = %v", err)
	}
	if deleted.ArtistCount != 0 || deleted.AlbumCount != 0 || deleted.TrackCount != 0 || deleted.Revision == updated.Revision {
		t.Fatalf("deleted summary = %+v", deleted)
	}
}
