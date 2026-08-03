// Package health exposes the minimal Agent bootstrap contract.
package health

import (
	"encoding/json"
	"log/slog"
	"net/http"
	"sync/atomic"
)

// BuildInfo is safe to expose without authentication during bootstrap.
type BuildInfo struct {
	Product              string `json:"product"`
	Version              string `json:"version"`
	ArchitectureBaseline string `json:"architecture_baseline"`
	Component            string `json:"component"`
	AgentID              string `json:"agent_id"`
	RegionID             string `json:"region_id"`
	SiteID               string `json:"site_id"`
	ScopeMode            string `json:"scope_mode"`
}

// Server owns health and diagnostic handlers. Readiness is changed atomically
// so shutdown can stop new work before the listener is closed.
type Server struct {
	ready atomic.Bool
	build BuildInfo
	log   *slog.Logger
}

func New(build BuildInfo, logger *slog.Logger) *Server {
	if logger == nil {
		logger = slog.Default()
	}
	return &Server{build: build, log: logger}
}

func (s *Server) SetReady(ready bool) {
	s.ready.Store(ready)
}

func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /health/live", s.live)
	mux.HandleFunc("GET /health/ready", s.readiness)
	mux.HandleFunc("GET /api/v1/system/build", s.buildInfo)
	return s.requestLog(mux)
}

func (s *Server) live(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, map[string]string{"status": "UP"})
}

func (s *Server) readiness(w http.ResponseWriter, _ *http.Request) {
	if !s.ready.Load() {
		writeJSON(w, http.StatusServiceUnavailable, map[string]string{"status": "DOWN"})
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "UP"})
}

func (s *Server) buildInfo(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, s.build)
}

func (s *Server) requestLog(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		s.log.Debug("agent http request", "method", r.Method, "path", r.URL.Path)
		next.ServeHTTP(w, r)
	})
}

func writeJSON(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	if err := json.NewEncoder(w).Encode(value); err != nil {
		// The response may already be committed; logging is the only safe action.
		slog.Error("encode health response", "error", err)
	}
}
