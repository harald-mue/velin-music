package httpapi

import (
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/harald-mue/velin-music/server/internal/library"
)

type libraryRootResponse struct {
	ID        string `json:"id"`
	Path      string `json:"path"`
	CreatedAt string `json:"created_at"`
}

type libraryRootListResponse struct {
	Items []libraryRootResponse `json:"items"`
}

type createLibraryRootRequest struct {
	Path string `json:"path"`
}

type scanRunResponse struct {
	ID           string  `json:"id"`
	RootID       string  `json:"root_id"`
	Status       string  `json:"status"`
	StartedAt    string  `json:"started_at"`
	FinishedAt   *string `json:"finished_at,omitempty"`
	FilesSeen    int     `json:"files_seen"`
	FilesIndexed int     `json:"files_indexed"`
	FilesRemoved int     `json:"files_removed"`
}

type scanRunListResponse struct {
	Items []scanRunResponse `json:"items"`
}

type scanErrorResponse struct {
	SourceName string `json:"source_name,omitempty"`
	ErrorCode  string `json:"error_code"`
	Message    string `json:"message"`
	CreatedAt  string `json:"created_at"`
}

type scanErrorListResponse struct {
	Items []scanErrorResponse `json:"items"`
	Total int                 `json:"total"`
}

type searchDiagnosticsResponse struct {
	Valid      bool     `json:"valid"`
	InputBytes int      `json:"input_bytes"`
	Terms      []string `json:"terms,omitempty"`
	MatchQuery string   `json:"match_query,omitempty"`
	Scope      string   `json:"scope,omitempty"`
	Message    string   `json:"message,omitempty"`
}

type searchPreviewTrackResponse struct {
	ID     string `json:"id"`
	Title  string `json:"title"`
	Artist string `json:"artist,omitempty"`
	Album  string `json:"album,omitempty"`
}

type searchDiagnosticsResultResponse struct {
	searchDiagnosticsResponse
	PreviewCount int                          `json:"preview_count"`
	HasMore      bool                         `json:"has_more"`
	Preview      []searchPreviewTrackResponse `json:"preview,omitempty"`
}

func registerAdminLibraryRoutes(mux *http.ServeMux, adminAuth func(http.Handler) http.Handler, adminMutate func(http.HandlerFunc) http.Handler, roots *library.Store, scans *library.ScanService, scanQueries *library.ScanQueryRepository, queries *library.QueryRepository) {
	mux.Handle("GET /api/v1/admin/library-roots", adminAuth(http.HandlerFunc(listLibraryRootsHandler(roots))))
	mux.Handle("POST /api/v1/admin/library-roots", adminMutate(createLibraryRootHandler(roots)))
	mux.Handle("DELETE /api/v1/admin/library-roots/{id}", adminMutate(deleteLibraryRootHandler(roots)))
	mux.Handle("POST /api/v1/admin/library-roots/{id}/scan", adminMutate(startRootScanHandler(scans)))
	mux.Handle("POST /api/v1/admin/scans", adminMutate(startAllScansHandler(scans)))
	mux.Handle("GET /api/v1/admin/scans", adminAuth(http.HandlerFunc(listScansHandler(scanQueries))))
	mux.Handle("GET /api/v1/admin/scans/{id}", adminAuth(http.HandlerFunc(getScanHandler(scanQueries))))
	mux.Handle("GET /api/v1/admin/scans/{id}/errors", adminAuth(http.HandlerFunc(listScanErrorsHandler(scanQueries))))
	mux.Handle("GET /api/v1/admin/search-diagnostics", adminAuth(http.HandlerFunc(searchDiagnosticsHandler(queries))))
}

func listLibraryRootsHandler(roots *library.Store) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		items, err := roots.ListRoots(r.Context())
		if err != nil {
			writeJSONError(w, http.StatusInternalServerError, "internal_error", "internal error")
			return
		}
		response := make([]libraryRootResponse, 0, len(items))
		for _, item := range items {
			response = append(response, libraryRootResponseFrom(item))
		}
		writeJSON(w, http.StatusOK, libraryRootListResponse{Items: response})
	}
}

func createLibraryRootHandler(roots *library.Store) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		body, err := io.ReadAll(io.LimitReader(r.Body, maxAdminRequestBytes+1))
		if err != nil {
			writeJSONError(w, http.StatusBadRequest, "invalid_request", "invalid request")
			return
		}
		if len(body) > maxAdminRequestBytes {
			writeJSONError(w, http.StatusBadRequest, "invalid_request", "invalid request")
			return
		}
		var request createLibraryRootRequest
		if err := json.Unmarshal(body, &request); err != nil {
			writeJSONError(w, http.StatusBadRequest, "invalid_request", "invalid request")
			return
		}
		if strings.TrimSpace(request.Path) == "" {
			writeJSONError(w, http.StatusBadRequest, "invalid_request", "invalid request")
			return
		}
		root, err := roots.AddRoot(r.Context(), request.Path)
		if errors.Is(err, library.ErrRootExists) {
			writeJSONError(w, http.StatusConflict, "root_exists", "library root already exists")
			return
		}
		if err != nil {
			writeJSONError(w, http.StatusBadRequest, "invalid_request", "invalid request")
			return
		}
		writeJSON(w, http.StatusCreated, libraryRootResponseFrom(root))
	}
}

func deleteLibraryRootHandler(roots *library.Store) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		rootID := strings.TrimSpace(r.PathValue("id"))
		if rootID == "" {
			writeJSONError(w, http.StatusBadRequest, "invalid_request", "invalid request")
			return
		}
		if err := roots.RemoveRoot(r.Context(), rootID); errors.Is(err, library.ErrRootNotFound) {
			writeJSONError(w, http.StatusNotFound, "not_found", "library root not found")
			return
		} else if err != nil {
			writeJSONError(w, http.StatusInternalServerError, "internal_error", "internal error")
			return
		}
		w.WriteHeader(http.StatusNoContent)
	}
}

func startRootScanHandler(scans *library.ScanService) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		rootID := strings.TrimSpace(r.PathValue("id"))
		if rootID == "" {
			writeJSONError(w, http.StatusBadRequest, "invalid_request", "invalid request")
			return
		}
		if err := scans.StartRoot(rootID); errors.Is(err, library.ErrRootNotFound) {
			writeJSONError(w, http.StatusNotFound, "not_found", "library root not found")
			return
		} else if errors.Is(err, library.ErrScanAlreadyRunning) {
			writeJSONError(w, http.StatusConflict, "scan_running", "scan already running")
			return
		} else if err != nil {
			writeJSONError(w, http.StatusInternalServerError, "internal_error", "internal error")
			return
		}
		w.WriteHeader(http.StatusAccepted)
	}
}

func startAllScansHandler(scans *library.ScanService) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if err := scans.StartAll(); errors.Is(err, library.ErrScanAlreadyRunning) {
			writeJSONError(w, http.StatusConflict, "scan_running", "scan already running")
			return
		} else if err != nil {
			writeJSONError(w, http.StatusInternalServerError, "internal_error", "internal error")
			return
		}
		w.WriteHeader(http.StatusAccepted)
	}
}

func listScansHandler(scanQueries *library.ScanQueryRepository) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		limit := 20
		if raw := strings.TrimSpace(r.URL.Query().Get("limit")); raw != "" {
			parsed, err := strconv.Atoi(raw)
			if err != nil || parsed <= 0 {
				writeJSONError(w, http.StatusBadRequest, "invalid_request", "invalid request")
				return
			}
			limit = parsed
		}
		runs, err := scanQueries.ListRecent(r.Context(), limit)
		if err != nil {
			writeJSONError(w, http.StatusInternalServerError, "internal_error", "internal error")
			return
		}
		items := make([]scanRunResponse, 0, len(runs))
		for _, run := range runs {
			items = append(items, scanRunResponseFrom(run))
		}
		writeJSON(w, http.StatusOK, scanRunListResponse{Items: items})
	}
}

func libraryRootResponseFrom(root library.Root) libraryRootResponse {
	return libraryRootResponse{
		ID:        root.ID,
		Path:      root.Path,
		CreatedAt: root.CreatedAt.UTC().Format(time.RFC3339Nano),
	}
}

func scanRunResponseFrom(run library.ScanRunRecord) scanRunResponse {
	response := scanRunResponse{
		ID:           run.ID,
		RootID:       run.RootID,
		Status:       run.Status,
		StartedAt:    run.StartedAt.UTC().Format(time.RFC3339Nano),
		FilesSeen:    run.FilesSeen,
		FilesIndexed: run.FilesIndexed,
		FilesRemoved: run.FilesRemoved,
	}
	if run.FinishedAt != nil {
		value := run.FinishedAt.UTC().Format(time.RFC3339Nano)
		response.FinishedAt = &value
	}
	return response
}

func getScanHandler(scanQueries *library.ScanQueryRepository) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		scanID := strings.TrimSpace(r.PathValue("id"))
		if scanID == "" {
			writeJSONError(w, http.StatusBadRequest, "invalid_request", "invalid request")
			return
		}
		run, err := scanQueries.GetByID(r.Context(), scanID)
		if errors.Is(err, library.ErrScanRunNotFound) {
			writeJSONError(w, http.StatusNotFound, "not_found", "scan run not found")
			return
		}
		if err != nil {
			writeJSONError(w, http.StatusInternalServerError, "internal_error", "internal error")
			return
		}
		writeJSON(w, http.StatusOK, scanRunResponseFrom(*run))
	}
}

func listScanErrorsHandler(scanQueries *library.ScanQueryRepository) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		scanID := strings.TrimSpace(r.PathValue("id"))
		if scanID == "" {
			writeJSONError(w, http.StatusBadRequest, "invalid_request", "invalid request")
			return
		}
		if _, err := scanQueries.GetByID(r.Context(), scanID); errors.Is(err, library.ErrScanRunNotFound) {
			writeJSONError(w, http.StatusNotFound, "not_found", "scan run not found")
			return
		} else if err != nil {
			writeJSONError(w, http.StatusInternalServerError, "internal_error", "internal error")
			return
		}
		limit := 50
		if raw := strings.TrimSpace(r.URL.Query().Get("limit")); raw != "" {
			parsed, err := strconv.Atoi(raw)
			if err != nil || parsed <= 0 {
				writeJSONError(w, http.StatusBadRequest, "invalid_request", "invalid request")
				return
			}
			limit = parsed
		}
		total, err := scanQueries.CountErrors(r.Context(), scanID)
		if err != nil {
			writeJSONError(w, http.StatusInternalServerError, "internal_error", "internal error")
			return
		}
		items, err := scanQueries.ListErrors(r.Context(), scanID, limit)
		if err != nil {
			writeJSONError(w, http.StatusInternalServerError, "internal_error", "internal error")
			return
		}
		response := make([]scanErrorResponse, 0, len(items))
		for _, item := range items {
			response = append(response, scanErrorResponse{
				SourceName: item.SourceName,
				ErrorCode:  item.ErrorCode,
				Message:    item.Message,
				CreatedAt:  item.CreatedAt.UTC().Format(time.RFC3339Nano),
			})
		}
		writeJSON(w, http.StatusOK, scanErrorListResponse{Items: response, Total: total})
	}
}

func searchDiagnosticsHandler(queries *library.QueryRepository) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		query := strings.TrimSpace(r.URL.Query().Get("q"))
		diag := library.DiagnoseSearchQuery(query)
		response := searchDiagnosticsResultResponse{
			searchDiagnosticsResponse: searchDiagnosticsResponse{
				Valid:      diag.Valid,
				InputBytes: diag.InputBytes,
				Terms:      diag.Terms,
				MatchQuery: diag.MatchQuery,
				Scope:      diag.Scope,
				Message:    diag.Message,
			},
		}
		if !diag.Valid {
			writeJSON(w, http.StatusOK, response)
			return
		}
		page, err := queries.SearchTracks(r.Context(), query, library.PageOptions{Limit: 10})
		if err != nil {
			writeJSONError(w, http.StatusInternalServerError, "internal_error", "internal error")
			return
		}
		response.PreviewCount = len(page.Items)
		response.HasMore = page.HasMore
		response.Preview = make([]searchPreviewTrackResponse, 0, len(page.Items))
		for _, track := range page.Items {
			response.Preview = append(response.Preview, searchPreviewTrackResponse{
				ID:     track.ID,
				Title:  track.Title,
				Artist: optionalJSONString(track.ArtistName),
				Album:  optionalJSONString(track.AlbumTitle),
			})
		}
		writeJSON(w, http.StatusOK, response)
	}
}

func optionalJSONString(value *string) string {
	if value == nil {
		return ""
	}
	return *value
}
