package httpapi

import (
	"errors"
	"net/http"
	"strconv"
	"strings"

	"github.com/harald-mue/velin-music/server/internal/auth"
	"github.com/harald-mue/velin-music/server/internal/library"
)

type libraryServices struct {
	tokens  *auth.TokenRepository
	queries *library.QueryRepository
	covers  *library.CoverReader
	streams *library.TrackStreamer
}

func registerLibraryRoutes(mux *http.ServeMux, services libraryServices) {
	deviceAuth := auth.RequireDeviceToken(services.tokens)
	mux.Handle("GET /api/v1/artists", deviceAuth(http.HandlerFunc(services.listArtistsHandler())))
	mux.Handle("GET /api/v1/artists/{id}", deviceAuth(http.HandlerFunc(services.getArtistHandler())))
	mux.Handle("GET /api/v1/albums", deviceAuth(http.HandlerFunc(services.listAlbumsHandler())))
	mux.Handle("GET /api/v1/albums/{id}", deviceAuth(http.HandlerFunc(services.getAlbumHandler())))
	mux.Handle("GET /api/v1/tracks", deviceAuth(http.HandlerFunc(services.listTracksHandler())))
	mux.Handle("GET /api/v1/tracks/{id}", deviceAuth(http.HandlerFunc(services.getTrackHandler())))
	mux.Handle("GET /api/v1/tracks/{id}/stream", deviceAuth(http.HandlerFunc(services.streamTrackHandler())))
	mux.Handle("HEAD /api/v1/tracks/{id}/stream", deviceAuth(http.HandlerFunc(services.streamTrackHandler())))
	mux.Handle("GET /api/v1/search", deviceAuth(http.HandlerFunc(services.searchTracksHandler())))
	mux.Handle("GET /api/v1/covers/{id}", deviceAuth(http.HandlerFunc(services.getCoverHandler())))
}

func (s libraryServices) listArtistsHandler() http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		options, err := parsePageOptions(r)
		if err != nil {
			writeJSONError(w, http.StatusBadRequest, "invalid_request", "invalid request")
			return
		}
		page, err := s.queries.ListArtists(r.Context(), options)
		if err != nil {
			writeRepositoryError(w, err)
			return
		}
		writeJSON(w, http.StatusOK, page)
	}
}

func (s libraryServices) getArtistHandler() http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		artist, err := s.queries.GetArtist(r.Context(), r.PathValue("id"))
		if err != nil {
			writeRepositoryError(w, err)
			return
		}
		writeJSON(w, http.StatusOK, artist)
	}
}

func (s libraryServices) listAlbumsHandler() http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		options, err := parsePageOptions(r)
		if err != nil {
			writeJSONError(w, http.StatusBadRequest, "invalid_request", "invalid request")
			return
		}
		page, err := s.queries.ListAlbums(r.Context(), options)
		if err != nil {
			writeRepositoryError(w, err)
			return
		}
		writeJSON(w, http.StatusOK, page)
	}
}

func (s libraryServices) getAlbumHandler() http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		album, err := s.queries.GetAlbum(r.Context(), r.PathValue("id"))
		if err != nil {
			writeRepositoryError(w, err)
			return
		}
		writeJSON(w, http.StatusOK, album)
	}
}

func (s libraryServices) listTracksHandler() http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		options, err := parsePageOptions(r)
		if err != nil {
			writeJSONError(w, http.StatusBadRequest, "invalid_request", "invalid request")
			return
		}
		page, err := s.queries.ListTracks(r.Context(), library.TrackListOptions{
			PageOptions: options,
			ArtistID:    r.URL.Query().Get("artist_id"),
			AlbumID:     r.URL.Query().Get("album_id"),
		})
		if err != nil {
			writeRepositoryError(w, err)
			return
		}
		writeJSON(w, http.StatusOK, page)
	}
}

func (s libraryServices) getTrackHandler() http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		track, err := s.queries.GetTrack(r.Context(), r.PathValue("id"))
		if err != nil {
			writeRepositoryError(w, err)
			return
		}
		writeJSON(w, http.StatusOK, track)
	}
}

func (s libraryServices) searchTracksHandler() http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		query := strings.TrimSpace(r.URL.Query().Get("q"))
		if query == "" {
			writeJSONError(w, http.StatusBadRequest, "invalid_request", "invalid request")
			return
		}
		options, err := parsePageOptions(r)
		if err != nil {
			writeJSONError(w, http.StatusBadRequest, "invalid_request", "invalid request")
			return
		}
		page, err := s.queries.SearchTracks(r.Context(), query, options)
		if err != nil {
			writeRepositoryError(w, err)
			return
		}
		writeJSON(w, http.StatusOK, page)
	}
}

func (s libraryServices) getCoverHandler() http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if s.covers == nil {
			writeJSONError(w, http.StatusInternalServerError, "internal_error", "internal error")
			return
		}
		file, content, err := s.covers.Open(r.Context(), r.PathValue("id"))
		if err != nil {
			writeRepositoryError(w, err)
			return
		}
		defer file.Close()
		w.Header().Set("Content-Type", content.MIMEType)
		w.Header().Set("Cache-Control", "private, max-age=3600")
		http.ServeContent(w, r, r.PathValue("id"), content.ModTime, file)
	}
}

func (s libraryServices) streamTrackHandler() http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if s.streams == nil {
			writeJSONError(w, http.StatusInternalServerError, "internal_error", "internal error")
			return
		}
		file, stream, err := s.streams.Open(r.Context(), r.PathValue("id"))
		if err != nil {
			writeRepositoryError(w, err)
			return
		}
		defer file.Close()
		w.Header().Set("Content-Type", stream.MIMEType)
		w.Header().Set("Cache-Control", "private, no-transform")
		http.ServeContent(w, r, r.PathValue("id"), stream.ModTime, file)
	}
}

func parsePageOptions(r *http.Request) (library.PageOptions, error) {
	options := library.PageOptions{
		Cursor: strings.TrimSpace(r.URL.Query().Get("cursor")),
	}
	limitValue := strings.TrimSpace(r.URL.Query().Get("limit"))
	if limitValue == "" {
		return options, nil
	}
	limit, err := strconv.Atoi(limitValue)
	if err != nil || limit < 0 {
		return library.PageOptions{}, errors.New("invalid limit")
	}
	options.Limit = limit
	return options, nil
}

func writeRepositoryError(w http.ResponseWriter, err error) {
	switch {
	case errors.Is(err, library.ErrNotFound):
		writeJSONError(w, http.StatusNotFound, "not_found", "library item not found")
	case errors.Is(err, library.ErrInvalidSearchQuery):
		writeJSONError(w, http.StatusBadRequest, "invalid_request", "invalid request")
	default:
		message := err.Error()
		if strings.Contains(message, "invalid page cursor") ||
			strings.Contains(message, "page limit") ||
			strings.Contains(message, "library item ID") ||
			strings.Contains(message, "library filter ID") ||
			strings.Contains(message, "cover ID") ||
			strings.Contains(message, "media file changed") ||
			strings.Contains(message, "media path") {
			writeJSONError(w, http.StatusBadRequest, "invalid_request", "invalid request")
			return
		}
		writeJSONError(w, http.StatusInternalServerError, "internal_error", "internal error")
	}
}
