// Package config loads and validates Agent startup configuration.
package config

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"strings"
	"time"
)

const (
	ScopeExplicitSubnets = "explicit_subnets"
	ScopeSiteDefault     = "site_default"
)

// Config contains only bootstrap values. Secrets are referenced elsewhere and
// are never accepted inline by this structure.
type Config struct {
	ListenAddress   string   `json:"listen_address"`
	ShutdownTimeout string   `json:"shutdown_timeout"`
	AgentID         string   `json:"agent_id"`
	RegionID        string   `json:"region_id"`
	SiteID          string   `json:"site_id"`
	AssignedSubnets []string `json:"assigned_subnets"`
}

// Load reads a single JSON object and rejects unknown fields to prevent silent
// configuration drift.
func Load(path string) (Config, error) {
	file, err := os.Open(path)
	if err != nil {
		return Config{}, fmt.Errorf("open agent configuration: %w", err)
	}
	defer file.Close()

	decoder := json.NewDecoder(file)
	decoder.DisallowUnknownFields()
	var cfg Config
	if err := decoder.Decode(&cfg); err != nil {
		return Config{}, fmt.Errorf("decode agent configuration: %w", err)
	}
	var trailing any
	if err := decoder.Decode(&trailing); !errors.Is(err, io.EOF) {
		return Config{}, errors.New("agent configuration must contain exactly one JSON object")
	}
	if err := cfg.Validate(); err != nil {
		return Config{}, err
	}
	return cfg, nil
}

// Validate enforces startup invariants before any listener is opened.
func (c Config) Validate() error {
	if strings.TrimSpace(c.ListenAddress) == "" {
		return errors.New("listen_address must not be blank")
	}
	if _, _, err := net.SplitHostPort(c.ListenAddress); err != nil {
		return fmt.Errorf("listen_address must be host:port: %w", err)
	}
	if strings.TrimSpace(c.AgentID) == "" {
		return errors.New("agent_id must not be blank")
	}
	if strings.TrimSpace(c.RegionID) == "" {
		return errors.New("region_id must not be blank")
	}
	if strings.TrimSpace(c.SiteID) == "" {
		return errors.New("site_id must not be blank")
	}
	if _, err := c.ShutdownDuration(); err != nil {
		return err
	}
	seen := make(map[string]struct{}, len(c.AssignedSubnets))
	for _, raw := range c.AssignedSubnets {
		subnet := strings.TrimSpace(raw)
		if subnet == "" {
			return errors.New("assigned_subnets must not contain blank values")
		}
		if _, _, err := net.ParseCIDR(subnet); err != nil {
			return fmt.Errorf("invalid assigned subnet %q: %w", subnet, err)
		}
		if _, exists := seen[subnet]; exists {
			return fmt.Errorf("duplicate assigned subnet %q", subnet)
		}
		seen[subnet] = struct{}{}
	}
	return nil
}

// ShutdownDuration returns the bounded graceful shutdown timeout.
func (c Config) ShutdownDuration() (time.Duration, error) {
	duration, err := time.ParseDuration(strings.TrimSpace(c.ShutdownTimeout))
	if err != nil {
		return 0, fmt.Errorf("invalid shutdown_timeout: %w", err)
	}
	if duration < time.Second || duration > 2*time.Minute {
		return 0, errors.New("shutdown_timeout must be between 1s and 2m")
	}
	return duration, nil
}

// ScopeMode implements ADR-0090: explicit subnets take precedence; otherwise
// the Agent receives every active discoverable subnet declared for its site.
func (c Config) ScopeMode() string {
	if len(c.AssignedSubnets) > 0 {
		return ScopeExplicitSubnets
	}
	return ScopeSiteDefault
}
