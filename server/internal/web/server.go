package web

import (
	"crypto/sha256"
	"crypto/subtle"
	"embed"
	"encoding/json"
	"errors"
	"html/template"
	"io"
	"net/http"
	"strings"
	"time"

	"connect/server/internal/auth"
	"connect/server/internal/model"
	"connect/server/internal/store"
)

const maxCollectionBytes = 4 << 20

//go:embed templates/*.html
var templates embed.FS

type Server struct {
	store        *store.Store
	collectToken string
	auth         *auth.Auth
	template     *template.Template
}

type dashboardData struct {
	User      auth.User
	CSRFToken string
	Devices   []model.Collection
}

func New(dataStore *store.Store, collectToken string, oidcAuth *auth.Auth) (*Server, error) {
	parsed, err := template.New("dashboard.html").Funcs(template.FuncMap{
		"timestamp": formatTimestamp,
	}).ParseFS(templates, "templates/*.html")
	if err != nil {
		return nil, err
	}
	return &Server{store: dataStore, collectToken: collectToken, auth: oidcAuth, template: parsed}, nil
}

func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "text/plain; charset=utf-8")
		w.Write([]byte("ok\n"))
	})
	mux.HandleFunc("POST /api/collect", s.collect)
	mux.HandleFunc("GET /login", s.auth.Login)
	mux.HandleFunc("GET /oidc/callback", s.auth.Callback)
	mux.HandleFunc("POST /logout", s.auth.Logout)
	mux.Handle("GET /", s.auth.Require(http.HandlerFunc(s.dashboard)))
	return securityHeaders(mux)
}

func (s *Server) collect(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cache-Control", "no-store")
	if !validBearer(r.Header.Get("Authorization"), s.collectToken) {
		w.Header().Set("WWW-Authenticate", "Bearer")
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	if mediaType := r.Header.Get("Content-Type"); !strings.HasPrefix(strings.ToLower(mediaType), "application/json") {
		http.Error(w, "content type must be application/json", http.StatusUnsupportedMediaType)
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, maxCollectionBytes)
	decoder := json.NewDecoder(r.Body)
	var collection model.Collection
	if err := decoder.Decode(&collection); err != nil {
		http.Error(w, "invalid JSON payload", http.StatusBadRequest)
		return
	}
	if err := ensureEOF(decoder); err != nil {
		http.Error(w, "request must contain one JSON object", http.StatusBadRequest)
		return
	}
	if err := validateCollection(&collection); err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	stored, err := s.store.Add(collection)
	if err != nil {
		http.Error(w, "could not store collection", http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusAccepted)
	json.NewEncoder(w).Encode(map[string]any{"status": "accepted", "receivedAt": stored.ReceivedAt})
}

func (s *Server) dashboard(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cache-Control", "no-store")
	user, csrfToken, ok := s.auth.Current(r)
	if !ok {
		http.Redirect(w, r, "/login", http.StatusFound)
		return
	}
	devices := s.store.List()
	for index := range devices {
		if len(devices[index].SMSMessages) > 20 {
			devices[index].SMSMessages = devices[index].SMSMessages[:20]
		}
		if len(devices[index].Notifications) > 20 {
			devices[index].Notifications = devices[index].Notifications[:20]
		}
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	if err := s.template.ExecuteTemplate(w, "dashboard.html", dashboardData{
		User: user, CSRFToken: csrfToken, Devices: devices,
	}); err != nil {
		http.Error(w, "could not render dashboard", http.StatusInternalServerError)
	}
}

func validateCollection(collection *model.Collection) error {
	collection.DeviceID = strings.TrimSpace(collection.DeviceID)
	collection.DeviceName = strings.TrimSpace(collection.DeviceName)
	if collection.DeviceID == "" || len(collection.DeviceID) > 128 {
		return errors.New("deviceId must contain 1 to 128 characters")
	}
	if len(collection.DeviceName) > 256 {
		return errors.New("deviceName must not exceed 256 characters")
	}
	if len(collection.SMSMessages) > 100 || len(collection.Notifications) > 100 {
		return errors.New("SMS and notification lists are limited to 100 entries")
	}
	return nil
}

func ensureEOF(decoder *json.Decoder) error {
	var extra any
	err := decoder.Decode(&extra)
	if errors.Is(err, io.EOF) {
		return nil
	}
	return errors.New("extra JSON value")
}

func validBearer(header, expected string) bool {
	const prefix = "Bearer "
	if !strings.HasPrefix(header, prefix) || expected == "" {
		return false
	}
	actualHash := sha256.Sum256([]byte(strings.TrimSpace(strings.TrimPrefix(header, prefix))))
	expectedHash := sha256.Sum256([]byte(expected))
	return subtle.ConstantTimeCompare(actualHash[:], expectedHash[:]) == 1
}

func formatTimestamp(milliseconds int64) string {
	if milliseconds <= 0 {
		return "unknown"
	}
	return time.UnixMilli(milliseconds).Format("2006-01-02 15:04:05 MST")
}

func securityHeaders(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Security-Policy", "default-src 'self'; style-src 'unsafe-inline'; frame-ancestors 'none'; base-uri 'none'")
		w.Header().Set("Referrer-Policy", "no-referrer")
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("X-Frame-Options", "DENY")
		next.ServeHTTP(w, r)
	})
}
