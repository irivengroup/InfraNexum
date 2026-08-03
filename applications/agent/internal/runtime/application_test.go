package runtime

import (
	"context"
	"io"
	"log/slog"
	"net"
	"net/http"
	"strings"
	"testing"
	"time"

	"infranexum/agent/internal/config"
)

func freeAddress(t *testing.T) string {
	t.Helper()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	address := listener.Addr().String()
	listener.Close()
	return address
}

func waitForTCPListener(t *testing.T, address string) {
	t.Helper()
	deadline := time.Now().Add(3 * time.Second)
	for {
		connection, err := net.DialTimeout("tcp", address, 100*time.Millisecond)
		if err == nil {
			if closeErr := connection.Close(); closeErr != nil {
				t.Fatalf("close readiness connection: %v", closeErr)
			}
			return
		}
		if time.Now().After(deadline) {
			t.Fatalf("agent listener %s did not become reachable: %v", address, err)
		}
		time.Sleep(10 * time.Millisecond)
	}
}

func TestNewRejectsInvalidConfiguration(t *testing.T) {
	_, err := New(config.Config{}, "test", nil)
	if err == nil {
		t.Fatal("New() error = nil, want validation error")
	}
}

func TestRunServesAndShutsDown(t *testing.T) {
	cfg := config.Config{ListenAddress: freeAddress(t), ShutdownTimeout: "2s", AgentID: "a", RegionID: "r", SiteID: "s"}
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	app, err := New(cfg, "test", logger)
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- app.Run(ctx) }()

	client := &http.Client{Timeout: 250 * time.Millisecond}
	deadline := time.Now().Add(3 * time.Second)
	for {
		resp, requestErr := client.Get("http://" + cfg.ListenAddress + "/health/ready")
		if requestErr == nil {
			resp.Body.Close()
			if resp.StatusCode == http.StatusOK {
				break
			}
		}
		if time.Now().After(deadline) {
			t.Fatalf("agent did not become ready: %v", requestErr)
		}
		time.Sleep(25 * time.Millisecond)
	}

	cancel()
	select {
	case err := <-done:
		if err != nil {
			t.Fatalf("Run() error = %v", err)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("Run() did not stop after cancellation")
	}
}

func TestRunReportsBindFailure(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	cfg := config.Config{ListenAddress: listener.Addr().String(), ShutdownTimeout: "2s", AgentID: "a", RegionID: "r", SiteID: "s"}
	app, err := New(cfg, "test", nil)
	if err != nil {
		t.Fatal(err)
	}
	err = app.Run(context.Background())
	if err == nil || !strings.Contains(err.Error(), "agent HTTP server") {
		t.Fatalf("Run() error = %v, want bind failure", err)
	}
}

func TestRunAcceptsAlreadyClosedServer(t *testing.T) {
	cfg := config.Config{ListenAddress: freeAddress(t), ShutdownTimeout: "2s", AgentID: "a", RegionID: "r", SiteID: "s"}
	app, err := New(cfg, "test", nil)
	if err != nil {
		t.Fatal(err)
	}
	if err := app.server.Close(); err != nil {
		t.Fatal(err)
	}
	if err := app.Run(context.Background()); err != nil {
		t.Fatalf("Run() error = %v", err)
	}
}

func TestRunReportsGracefulShutdownTimeout(t *testing.T) {
	cfg := config.Config{ListenAddress: freeAddress(t), ShutdownTimeout: "1s", AgentID: "a", RegionID: "r", SiteID: "s"}
	app, err := New(cfg, "test", nil)
	if err != nil {
		t.Fatal(err)
	}
	entered := make(chan struct{})
	release := make(chan struct{})
	app.server.Handler = http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		close(entered)
		<-release
		w.WriteHeader(http.StatusNoContent)
	})

	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- app.Run(ctx) }()
	waitForTCPListener(t, cfg.ListenAddress)

	requestDone := make(chan struct{})
	go func() {
		defer close(requestDone)
		_, _ = http.Get("http://" + cfg.ListenAddress + "/blocked")
	}()
	select {
	case <-entered:
	case <-time.After(3 * time.Second):
		close(release)
		t.Fatal("blocking handler was not entered")
	}
	cancel()
	select {
	case err := <-done:
		if err == nil || !strings.Contains(err.Error(), "shutdown agent HTTP server") {
			close(release)
			t.Fatalf("Run() error = %v, want shutdown timeout", err)
		}
	case <-time.After(3 * time.Second):
		close(release)
		t.Fatal("Run() did not report shutdown timeout")
	}
	close(release)
	_ = app.server.Close()
	select {
	case <-requestDone:
	case <-time.After(2 * time.Second):
		t.Fatal("blocked request did not finish")
	}
}
