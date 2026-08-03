package health

import (
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"testing"
)

func newTestServer() *Server {
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	return New(BuildInfo{Product: "InfraNexum", Version: "test", Component: "AGENT", AgentID: "a", RegionID: "r", SiteID: "s", ScopeMode: "site_default"}, logger)
}

func TestLivenessAndBuildInfo(t *testing.T) {
	server := newTestServer()
	tests := []struct {
		path string
		want int
	}{
		{"/health/live", http.StatusOK},
		{"/api/v1/system/build", http.StatusOK},
	}
	for _, tt := range tests {
		recorder := httptest.NewRecorder()
		server.Handler().ServeHTTP(recorder, httptest.NewRequest(http.MethodGet, tt.path, nil))
		if recorder.Code != tt.want {
			t.Fatalf("GET %s status = %d, want %d", tt.path, recorder.Code, tt.want)
		}
		if recorder.Header().Get("Cache-Control") != "no-store" {
			t.Fatalf("GET %s missing no-store", tt.path)
		}
	}
}

func TestReadinessTransitions(t *testing.T) {
	server := newTestServer()
	assertStatus := func(want int) {
		t.Helper()
		recorder := httptest.NewRecorder()
		server.Handler().ServeHTTP(recorder, httptest.NewRequest(http.MethodGet, "/health/ready", nil))
		if recorder.Code != want {
			t.Fatalf("readiness status = %d, want %d", recorder.Code, want)
		}
	}
	assertStatus(http.StatusServiceUnavailable)
	server.SetReady(true)
	assertStatus(http.StatusOK)
	server.SetReady(false)
	assertStatus(http.StatusServiceUnavailable)
}

func TestBuildPayload(t *testing.T) {
	server := newTestServer()
	recorder := httptest.NewRecorder()
	server.Handler().ServeHTTP(recorder, httptest.NewRequest(http.MethodGet, "/api/v1/system/build", nil))
	var payload BuildInfo
	if err := json.Unmarshal(recorder.Body.Bytes(), &payload); err != nil {
		t.Fatalf("decode payload: %v", err)
	}
	if payload.Product != "InfraNexum" || payload.Component != "AGENT" {
		t.Fatalf("unexpected payload: %+v", payload)
	}
}

func TestNewAcceptsNilLogger(t *testing.T) {
	if New(BuildInfo{}, nil) == nil {
		t.Fatal("New() returned nil")
	}
}

type failedResponseWriter struct {
	header http.Header
	status int
}

func (writer *failedResponseWriter) Header() http.Header {
	if writer.header == nil {
		writer.header = make(http.Header)
	}
	return writer.header
}

func (writer *failedResponseWriter) WriteHeader(status int) {
	writer.status = status
}

func (*failedResponseWriter) Write([]byte) (int, error) {
	return 0, io.ErrClosedPipe
}

func TestWriteJSONToleratesCommittedResponseFailure(t *testing.T) {
	writer := &failedResponseWriter{}
	writeJSON(writer, http.StatusOK, map[string]string{"status": "UP"})
	if writer.status != http.StatusOK {
		t.Fatalf("status = %d, want %d", writer.status, http.StatusOK)
	}
}
