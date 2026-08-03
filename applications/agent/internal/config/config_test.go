package config

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func validConfig() Config {
	return Config{
		ListenAddress:   "127.0.0.1:8091",
		ShutdownTimeout: "10s",
		AgentID:         "agent-1",
		RegionID:        "region-1",
		SiteID:          "site-1",
	}
}

func TestValidateAcceptsSiteDefaultScope(t *testing.T) {
	cfg := validConfig()
	if err := cfg.Validate(); err != nil {
		t.Fatalf("Validate() error = %v", err)
	}
	if got := cfg.ScopeMode(); got != ScopeSiteDefault {
		t.Fatalf("ScopeMode() = %q, want %q", got, ScopeSiteDefault)
	}
}

func TestValidateAcceptsExplicitSubnets(t *testing.T) {
	cfg := validConfig()
	cfg.AssignedSubnets = []string{"10.0.0.0/24", "2001:db8::/64"}
	if err := cfg.Validate(); err != nil {
		t.Fatalf("Validate() error = %v", err)
	}
	if got := cfg.ScopeMode(); got != ScopeExplicitSubnets {
		t.Fatalf("ScopeMode() = %q, want %q", got, ScopeExplicitSubnets)
	}
}

func TestValidateRejectsInvalidInputs(t *testing.T) {
	tests := []struct {
		name string
		edit func(*Config)
		want string
	}{
		{"blank listen", func(c *Config) { c.ListenAddress = " " }, "listen_address"},
		{"bad listen", func(c *Config) { c.ListenAddress = "localhost" }, "host:port"},
		{"blank agent", func(c *Config) { c.AgentID = "" }, "agent_id"},
		{"blank region", func(c *Config) { c.RegionID = "" }, "region_id"},
		{"blank site", func(c *Config) { c.SiteID = "" }, "site_id"},
		{"bad timeout", func(c *Config) { c.ShutdownTimeout = "never" }, "shutdown_timeout"},
		{"short timeout", func(c *Config) { c.ShutdownTimeout = "500ms" }, "between"},
		{"long timeout", func(c *Config) { c.ShutdownTimeout = "3m" }, "between"},
		{"blank subnet", func(c *Config) { c.AssignedSubnets = []string{""} }, "blank"},
		{"bad subnet", func(c *Config) { c.AssignedSubnets = []string{"10.0.0.1"} }, "invalid"},
		{"duplicate subnet", func(c *Config) { c.AssignedSubnets = []string{"10.0.0.0/24", "10.0.0.0/24"} }, "duplicate"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			cfg := validConfig()
			tt.edit(&cfg)
			err := cfg.Validate()
			if err == nil || !strings.Contains(err.Error(), tt.want) {
				t.Fatalf("Validate() error = %v, want substring %q", err, tt.want)
			}
		})
	}
}

func TestShutdownDuration(t *testing.T) {
	cfg := validConfig()
	got, err := cfg.ShutdownDuration()
	if err != nil {
		t.Fatalf("ShutdownDuration() error = %v", err)
	}
	if got != 10*time.Second {
		t.Fatalf("ShutdownDuration() = %v, want 10s", got)
	}
}

func TestLoadRejectsUnknownAndMalformedConfiguration(t *testing.T) {
	dir := t.TempDir()
	unknown := filepath.Join(dir, "unknown.json")
	if err := os.WriteFile(unknown, []byte(`{"listen_address":"127.0.0.1:1","shutdown_timeout":"1s","agent_id":"a","region_id":"r","site_id":"s","extra":true}`), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := Load(unknown); err == nil || !strings.Contains(err.Error(), "unknown field") {
		t.Fatalf("Load() error = %v, want unknown field", err)
	}

	malformed := filepath.Join(dir, "malformed.json")
	if err := os.WriteFile(malformed, []byte(`{"listen_address":`), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := Load(malformed); err == nil || !strings.Contains(err.Error(), "decode") {
		t.Fatalf("Load() error = %v, want decode error", err)
	}
}

func TestLoadRejectsConfigurationThatFailsValidation(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "invalid.json")
	content := `{"listen_address":"invalid","shutdown_timeout":"10s","agent_id":"a","region_id":"r","site_id":"s","assigned_subnets":[]}`
	if err := os.WriteFile(path, []byte(content), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := Load(path); err == nil || !strings.Contains(err.Error(), "host:port") {
		t.Fatalf("Load() error = %v, want validation error", err)
	}
}

func TestLoadRejectsTrailingJSONValue(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "trailing.json")
	content := `{"listen_address":"127.0.0.1:8091","shutdown_timeout":"10s","agent_id":"a","region_id":"r","site_id":"s","assigned_subnets":[]} {}`
	if err := os.WriteFile(path, []byte(content), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := Load(path); err == nil || !strings.Contains(err.Error(), "exactly one") {
		t.Fatalf("Load() error = %v, want trailing value error", err)
	}
}

func TestLoadValidConfigurationAndMissingFile(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "agent.json")
	content := `{"listen_address":"127.0.0.1:8091","shutdown_timeout":"10s","agent_id":"a","region_id":"r","site_id":"s","assigned_subnets":[]}`
	if err := os.WriteFile(path, []byte(content), 0o600); err != nil {
		t.Fatal(err)
	}
	cfg, err := Load(path)
	if err != nil {
		t.Fatalf("Load() error = %v", err)
	}
	if cfg.AgentID != "a" {
		t.Fatalf("AgentID = %q, want a", cfg.AgentID)
	}
	if _, err := Load(filepath.Join(dir, "missing.json")); err == nil || !strings.Contains(err.Error(), "open") {
		t.Fatalf("Load(missing) error = %v, want open error", err)
	}
}
