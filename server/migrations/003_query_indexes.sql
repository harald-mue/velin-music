-- Ordering and relationship indexes for bounded library queries.

CREATE INDEX albums_title_order_idx
    ON albums(title COLLATE NOCASE, id);

CREATE INDEX tracks_title_order_idx
    ON tracks(title COLLATE NOCASE, id);

CREATE INDEX tracks_artist_title_order_idx
    ON tracks(artist_id, title COLLATE NOCASE, id);

CREATE INDEX tracks_album_title_order_idx
    ON tracks(album_id, title COLLATE NOCASE, id);
