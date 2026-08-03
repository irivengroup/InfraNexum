package main

import (
	"bytes"
	"context"
	"io"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func testConfigFile(t *testing.T, address string) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "agent.json")
	content := `{"listen_address":"` + address + `","shutdown_timeout":"2s","agent_id":"agent-1","region_id":"eu-west","site_id":"paris-1","assigned_subnets":[]}`
	if err := os.WriteFile(path, []byte(content), 0o600); err != nil {
		t.Fatal(err)
	}
	return path
}

func freeAddress(t *testing.T) string {
	t.Helper()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	address := listener.Addr().String()
	if err := listener.Close(); err != nil {
		t.Fatal(err)
	}
	return address
}

func TestRunVersion(t *testing.T) {
	var output bytes.Buffer
	if err := run(context.Background(), []string{"--version"}, &output); err != nil {
		t.Fatalf("run() error = %v", err)
	}
	if !strings.Contains(output.String(), "infranexum-agent") {
		t.Fatalf("output = %q", output.String())
	}
}

func TestRunUsesConfigurationEnvironmentAndStops(t *testing.T) {
	address := freeAddress(t)
	t.Setenv("INFRANEXUM_AGENT_CONFIG", testConfigFile(t, address))
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- run(ctx, nil, io.Discard) }()

	client := &http.Client{Timeout: 250 * time.Millisecond}
	deadline := time.Now().Add(3 * time.Second)
	for {
		response, err := client.Get("http://" + address + "/health/ready")
		if err == nil {
			response.Body.Close()
			if response.StatusCode == http.StatusOK {
				break
			}
		}
		if time.Now().After(deadline) {
			t.Fatalf("Agent did not become ready: %v", err)
		}
		time.Sleep(25 * time.Millisecond)
	}

	cancel()
	select {
	case err := <-done:
		if err != nil {
			t.Fatalf("run() error = %v", err)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("run() did not stop")
	}
}

func TestRunRejectsUnknownFlagAndMissingConfig(t *testing.T) {
	if err := run(context.Background(), []string{"--unknown"}, io.Discard); err == nil {
		t.Fatal("run() error = nil, want flag error")
	}
	if err := run(context.Background(), []string{"--config", "/definitely/missing.json"}, io.Discard); err == nil {
		t.Fatal("run() error = nil, want configuration error")
	}
}

func TestRunRejectsInvalidBuildVersion(t *testing.T) {
	address := freeAddress(t)
	configPath := testConfigFile(t, address)
	originalVersion := version
	version = " "
	defer func() { version = originalVersion }()
	if err := run(context.Background(), []string{"--config", configPath}, io.Discard); err == nil || !strings.Contains(err.Error(), "version") {
		t.Fatalf("run() error = %v, want version validation error", err)
	}
}

func TestRunReportsOutputFailure(t *testing.T) {
	writer := failingWriter{}
	if err := run(context.Background(), []string{"--version"}, writer); err == nil {
		t.Fatal("run() error = nil, want output error")
	}
}

func TestMainReturnsSuccessAndFailureWithoutExitingTestProcess(t *testing.T) {
	originalExit := exitProcess
	originalArgs := os.Args
	defer func() {
		exitProcess = originalExit
		os.Args = originalArgs
	}()

	codes := make([]int, 0, 2)
	exitProcess = func(code int) { codes = append(codes, code) }
	os.Args = []string{"infranexum-agent", "--version"}
	main()
	os.Args = []string{"infranexum-agent", "--unknown"}
	main()

	if len(codes) != 2 || codes[0] != 0 || codes[1] != 1 {
		t.Fatalf("exit codes = %v, want [0 1]", codes)
	}
}

type failingWriter struct{}

func (failingWriter) Write([]byte) (int, error) {
	return 0, io.ErrClosedPipe
}
