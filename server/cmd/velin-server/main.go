package main

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"
	"time"

	"github.com/harald-mue/velin-music/server/internal/config"
	"github.com/harald-mue/velin-music/server/internal/db"
	"github.com/harald-mue/velin-music/server/internal/httpapi"
)

func main() {
	logger := slog.New(slog.NewJSONHandler(os.Stderr, &slog.HandlerOptions{Level: slog.LevelInfo}))
	slog.SetDefault(logger)

	cfg, err := config.Load()
	if err != nil {
		logger.Error("load configuration failed")
		os.Exit(1)
	}

	if err := config.EnsureDataDir(cfg.DataDir); err != nil {
		logger.Error("prepare data directory failed")
		os.Exit(1)
	}

	database, err := db.Open(context.Background(), filepath.Join(cfg.DataDir, "velin.db"))
	if err != nil {
		logger.Error("open database failed")
		os.Exit(1)
	}
	defer database.Close()

	api, err := httpapi.New(httpapi.Config{
		Version:       cfg.Version,
		PublicURL:     cfg.PublicURL,
		SecureCookies: cfg.SecureCookies,
		DataDir:       cfg.DataDir,
		ScanOnStartup: cfg.ScanOnStartup,
		ScanInterval:  cfg.ScanInterval,
	}, database)
	if err != nil {
		logger.Error("configure http api failed")
		os.Exit(1)
	}
	defer api.Stop()

	server := &http.Server{
		Addr:              cfg.HTTPAddr,
		Handler:           api.Handler(),
		ReadHeaderTimeout: 5 * time.Second,
		IdleTimeout:       60 * time.Second,
		WriteTimeout:      30 * time.Second,
	}

	serverErr := make(chan error, 1)
	go func() {
		logger.Info("starting Velin server", "addr", cfg.HTTPAddr, "version", cfg.Version)
		serverErr <- server.ListenAndServe()
	}()

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	select {
	case err := <-serverErr:
		if !errors.Is(err, http.ErrServerClosed) {
			logger.Error("server stopped unexpectedly", "error", err)
			os.Exit(1)
		}
	case <-ctx.Done():
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		if err := server.Shutdown(shutdownCtx); err != nil {
			logger.Error("server shutdown failed", "error", err)
			os.Exit(1)
		}
		logger.Info("server stopped")
	}
}
