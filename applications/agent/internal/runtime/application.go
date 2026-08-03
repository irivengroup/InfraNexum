// Package runtime owns the Agent process lifecycle.
package runtime

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"strings"
	"time"

	"infranexum/agent/internal/config"
	"infranexum/agent/internal/health"
)

// Application composes configuration and transport without embedding future
// Discovery rules in the process entry point.
type Application struct {
	config          config.Config
	server          *http.Server
	health          *health.Server
	log             *slog.Logger
	shutdownTimeout time.Duration
}

func New(cfg config.Config, version string, logger *slog.Logger) (*Application, error) {
	shutdownTimeout, err := cfg.ShutdownDuration()
	if err != nil {
		return nil, err
	}
	if err := cfg.Validate(); err != nil {
		return nil, err
	}
	version = strings.TrimSpace(version)
	if version == "" {
		return nil, errors.New("Agent version must not be blank")
	}
	if logger == nil {
		logger = slog.Default()
	}
	healthServer := health.New(health.BuildInfo{
		Product:              "InfraNexum",
		Version:              version,
		ArchitectureBaseline: "2.0.0-draft.21",
		Component:            "AGENT",
		AgentID:              cfg.AgentID,
		RegionID:             cfg.RegionID,
		SiteID:               cfg.SiteID,
		ScopeMode:            cfg.ScopeMode(),
	}, logger)
	server := &http.Server{
		Addr:              cfg.ListenAddress,
		Handler:           healthServer.Handler(),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       10 * time.Second,
		WriteTimeout:      10 * time.Second,
		IdleTimeout:       60 * time.Second,
	}
	return &Application{
		config:          cfg,
		server:          server,
		health:          healthServer,
		log:             logger,
		shutdownTimeout: shutdownTimeout,
	}, nil
}

func (a *Application) Run(ctx context.Context) error {
	a.health.SetReady(true)
	errCh := make(chan error, 1)
	go func() {
		a.log.Info("InfraNexum Agent listening", "address", a.server.Addr, "agent_id", a.config.AgentID, "scope_mode", a.config.ScopeMode())
		errCh <- a.server.ListenAndServe()
	}()

	select {
	case err := <-errCh:
		if errors.Is(err, http.ErrServerClosed) {
			return nil
		}
		return fmt.Errorf("agent HTTP server: %w", err)
	case <-ctx.Done():
		a.health.SetReady(false)
		shutdownCtx, cancel := context.WithTimeout(context.Background(), a.shutdownTimeout)
		defer cancel()
		if err := a.server.Shutdown(shutdownCtx); err != nil {
			return fmt.Errorf("shutdown agent HTTP server: %w", err)
		}
		return nil
	}
}
