package admin

import (
	"bytes"
	"html/template"
	"net/http"
)

type pageData struct {
	Title             string
	BodyHTML          template.HTML
	Authenticated     bool
	Username          string
	CSRFToken         string
	Version           string
	ActiveNav         string
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
	LatestScanStatus   string
	LatestScanStarted  string
	LatestScanFinished string
	ScanRunning        bool
}

type scanView struct {
	ID           string
	RootID       string
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
	http.Redirect(w, r, location, status)
}
