package admin

import (
	"bytes"
	"html/template"
	"net/http"
	"path"
	"strings"
)

type pageData struct {
	Title             string
	BodyHTML          template.HTML
	Authenticated     bool
	Username          string
	CSRFToken         string
	Version           string
	ActiveNav         string
	AdminURLPrefix    string
	Error             string
	ServerURL         string
	Devices           []deviceView
	PairingDeviceName string
	PairingExpiresAt  string
	PairingServerURL  string
	PairingCode       string
	PairingQRPayload  string
	PairingQRImageURL template.URL
	Roots             []rootView
	RecentScans       []scanView
	ScanRunning       bool
	Scan              scanView
	ScanErrors        []scanErrorView
	ScanErrorTotal    int
	SearchQuery       string
	SearchDiag        searchDiagView
	SearchResults     []searchResultView
	SearchResultCount int
	SearchHasMore     bool
}

type rootView struct {
	ID                 string
	Path               string
	CreatedAt          string
	LatestScanID       string
	LatestScanStatus   string
	LatestScanStarted  string
	LatestScanFinished string
	LatestFilesSeen    int
	ScanRunning        bool
}

type scanView struct {
	ID           string
	RootID       string
	RootPath     string
	Status       string
	StartedAt    string
	FinishedAt   string
	FilesSeen    int
	FilesIndexed int
	FilesRemoved int
}

type scanErrorView struct {
	SourceName string
	ErrorCode  string
	Message    string
	CreatedAt  string
}

type searchDiagView struct {
	Valid      bool
	InputBytes int
	Terms      []string
	MatchQuery string
	Scope      string
	Message    string
}

type searchResultView struct {
	ID     string
	Title  string
	Artist string
	Album  string
}

type deviceView struct {
	ID         string
	Name       string
	CreatedAt  string
	LastUsedAt string
	RevokedAt  string
}

func parseTemplates() (*template.Template, error) {
	return template.New("layout").ParseFS(assets, "templates/*.html")
}

func (h *Handler) render(w http.ResponseWriter, pageName string, data pageData) {
	var body bytes.Buffer
	if err := h.tmpl.ExecuteTemplate(&body, pageName+"-body", data); err != nil {
		http.Error(w, "template error", http.StatusInternalServerError)
		return
	}
	data.BodyHTML = template.HTML(body.String())
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	if err := h.tmpl.ExecuteTemplate(w, pageName+".html", data); err != nil {
		http.Error(w, "template error", http.StatusInternalServerError)
	}
}

func (h *Handler) redirect(w http.ResponseWriter, r *http.Request, location string, status int) {
	if location == "/admin" || strings.HasPrefix(location, "/admin/") {
		w.Header().Set("Location", relativeAdminLocation(r.URL.Path, location))
		w.WriteHeader(status)
		return
	}
	http.Redirect(w, r, location, status)
}

// relativeAdminLocation preserves an external reverse-proxy prefix because a
// browser resolves it relative to the URL visible in its address bar.
func relativeAdminLocation(requestPath, targetPath string) string {
	from := splitURLPath(path.Dir(requestPath))
	to := splitURLPath(targetPath)
	common := 0
	for common < len(from) && common < len(to) && from[common] == to[common] {
		common++
	}
	parts := make([]string, 0, len(from)-common+len(to)-common+1)
	for range from[common:] {
		parts = append(parts, "..")
	}
	if common == len(to) && len(to) > 0 && len(from) > common && !strings.HasSuffix(targetPath, "/") {
		parts = append(parts, "..", to[len(to)-1])
	} else {
		parts = append(parts, to[common:]...)
	}
	if len(parts) == 0 {
		return "./"
	}
	result := strings.Join(parts, "/")
	if strings.HasSuffix(targetPath, "/") {
		result += "/"
	}
	return result
}

func splitURLPath(value string) []string {
	value = strings.Trim(path.Clean("/"+value), "/")
	if value == "" || value == "." {
		return nil
	}
	return strings.Split(value, "/")
}
