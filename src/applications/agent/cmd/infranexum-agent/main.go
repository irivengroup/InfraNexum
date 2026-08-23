package main

import (
	"context"
	"flag"
	"fmt"
	"io"
	"log/slog"
	"os"
	"os/signal"
	"syscall"

	"infranexum/agent/internal/config"
	agentruntime "infranexum/agent/internal/runtime"
)

var (
	version     = "2.0.0-alpha.0.132"
	exitProcess = os.Exit
)

func main() {
	exitProcess(mainCode(os.Args[1:]))
}

func mainCode(args []string) int {
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()
	if err := run(ctx, args, os.Stdout); err != nil {
		slog.Error("InfraNexum Agent stopped", "error", err)
		return 1
	}
	return 0
}

func run(ctx context.Context, args []string, stdout io.Writer) error {
	flags := flag.NewFlagSet("infranexum-agent", flag.ContinueOnError)
	flags.SetOutput(stdout)
	defaultConfig := os.Getenv("INFRANEXUM_AGENT_CONFIG")
	if defaultConfig == "" {
		defaultConfig = "/etc/infranexum/agent.json"
	}
	configPath := flags.String("config", defaultConfig, "path to the Agent JSON configuration")
	showVersion := flags.Bool("version", false, "print the Agent version and exit")
	if err := flags.Parse(args); err != nil {
		return err
	}
	if *showVersion {
		_, err := fmt.Fprintf(stdout, "infranexum-agent %s\n", version)
		return err
	}
	cfg, err := config.Load(*configPath)
	if err != nil {
		return err
	}
	logger := slog.New(slog.NewJSONHandler(stdout, &slog.HandlerOptions{Level: slog.LevelInfo}))
	app, err := agentruntime.New(cfg, version, logger)
	if err != nil {
		return err
	}
	return app.Run(ctx)
}
