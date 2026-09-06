package library

import (
	"context"
	"crypto/sha256"
	"encoding/base64"
	"fmt"
)

// Summary is a compact exact view of the indexed library. Revision is opaque
// to clients and changes whenever an indexed track row or cached public model changes.
type Summary struct {
	ArtistCount int    `json:"artist_count"`
	AlbumCount  int    `json:"album_count"`
	TrackCount  int    `json:"track_count"`
	Revision    string `json:"revision"`
}

// Summary returns exact public entity counts and an opaque cache revision from
// one SQLite read snapshot.
func (r *QueryRepository) Summary(ctx context.Context) (Summary, error) {
	var summary Summary
	if err := r.check(ctx); err != nil {
		return summary, err
	}
	var revision int64
	err := r.db.QueryRowContext(ctx, `
		SELECT
			(SELECT COUNT(*) FROM (
				SELECT artist_id AS id FROM tracks WHERE artist_id IS NOT NULL
				UNION
				SELECT album_artist_id AS id FROM tracks WHERE album_artist_id IS NOT NULL
			)),
			(SELECT COUNT(DISTINCT album_id) FROM tracks WHERE album_id IS NOT NULL),
			(SELECT COUNT(*) FROM tracks),
			(SELECT revision FROM library_state WHERE singleton = 1)
	`).Scan(&summary.ArtistCount, &summary.AlbumCount, &summary.TrackCount, &revision)
	if err != nil {
		return Summary{}, fmt.Errorf("summarize library: %w", err)
	}
	summary.Revision = opaqueLibraryRevision(revision)
	return summary, nil
}

func opaqueLibraryRevision(revision int64) string {
	digest := sha256.Sum256([]byte(fmt.Sprintf("velin-library-revision-v2:%d", revision)))
	return base64.RawURLEncoding.EncodeToString(digest[:16])
}
