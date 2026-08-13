package main

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"connect/server/internal/auth"
	"connect/server/internal/mcp"
	"connect/server/internal/rostack"
	"connect/server/internal/store"
	"connect/server/internal/web"
	"github.com/coreos/go-oidc/v3/oidc"
)

type config struct {
	webAddr           string
	mcpAddr           string
	dataFile          string
	collectToken      string
	mcpToken          string
	mcpAllowedOrigins []string
	oidcIssuerURL     string
	oidcClientID      string
	oidcClientSecret  string
	oidcRedirectURL   string
	tlsCertFile       string
	tlsKeyFile        string
	rostackBaseURL    string
	rostackToken      string
	rostackAPIVersion string
}

func main() {
	if err := run(); err != nil {
		slog.Error("server stopped", "error", err)
		os.Exit(1)
	}
}

func run() error {
	cfg, err := loadConfig()
	if err != nil {
		return err
	}
	dataStore, err := store.Open(cfg.dataFile)
	if err != nil {
		return err
	}
	defer dataStore.Close()

	discoveryContext, cancelDiscovery := context.WithTimeout(context.Background(), 20*time.Second)
	provider, err := oidc.NewProvider(discoveryContext, cfg.oidcIssuerURL)
	cancelDiscovery()
	if err != nil {
		return fmt.Errorf("discover OIDC provider: %w", err)
	}
	oidcAuth, err := auth.New(provider, auth.Config{
		ClientID: cfg.oidcClientID, ClientSecret: cfg.oidcClientSecret, RedirectURL: cfg.oidcRedirectURL,
	})
	if err != nil {
		return err
	}
	rostackServer, err := rostack.New(dataStore, rostack.Config{
		BaseURL: cfg.rostackBaseURL, Token: cfg.rostackToken, APIVersion: cfg.rostackAPIVersion,
	})
	if err != nil {
		return fmt.Errorf("create rostack server: %w", err)
	}
	webHandler, err := web.New(dataStore, cfg.collectToken, oidcAuth, rostackServer)
	if err != nil {
		return fmt.Errorf("create web server: %w", err)
	}

	webServer := &http.Server{
		Addr: cfg.webAddr, Handler: webHandler.Handler(), ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout: 30 * time.Second, WriteTimeout: 30 * time.Second, IdleTimeout: 2 * time.Minute,
	}
	mcpServer := &http.Server{
		Addr: cfg.mcpAddr, Handler: mcp.New(dataStore, cfg.mcpToken, cfg.mcpAllowedOrigins).Handler(),
		ReadHeaderTimeout: 10 * time.Second, ReadTimeout: 30 * time.Second,
		WriteTimeout: 30 * time.Second, IdleTimeout: 2 * time.Minute,
	}

	errCh := make(chan error, 2)
	go serve("web", webServer, cfg.tlsCertFile, cfg.tlsKeyFile, errCh)
	go serve("mcp", mcpServer, cfg.tlsCertFile, cfg.tlsKeyFile, errCh)
	slog.Info("connect servers started", "web", cfg.webAddr, "mcp", cfg.mcpAddr)

	signalContext, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()
	var serveErr error
	select {
	case <-signalContext.Done():
		slog.Info("shutting down")
	case serveErr = <-errCh:
		slog.Error("listener failed; shutting down both servers", "error", serveErr)
	}
	shutdownContext, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	webErr := webServer.Shutdown(shutdownContext)
	mcpErr := mcpServer.Shutdown(shutdownContext)
	return errors.Join(serveErr, webErr, mcpErr)
}

func serve(name string, server *http.Server, certFile, keyFile string, errCh chan<- error) {
	var err error
	if certFile != "" {
		err = server.ListenAndServeTLS(certFile, keyFile)
	} else {
		err = server.ListenAndServe()
	}
	if err != nil && !errors.Is(err, http.ErrServerClosed) {
		errCh <- fmt.Errorf("%s server: %w", name, err)
	}
}

func loadConfig() (config, error) {
	cfg := config{
		webAddr:           envOr("WEB_ADDR", ":8080"),
		mcpAddr:           envOr("MCP_ADDR", ":8081"),
		dataFile:          envOr("DATA_FILE", "./data/collections.jsonl"),
		collectToken:      os.Getenv("COLLECT_TOKEN"),
		mcpToken:          os.Getenv("MCP_TOKEN"),
		mcpAllowedOrigins: splitCSV(os.Getenv("MCP_ALLOWED_ORIGINS")),
		oidcIssuerURL:     os.Getenv("OIDC_ISSUER_URL"),
		oidcClientID:      os.Getenv("OIDC_CLIENT_ID"),
		oidcClientSecret:  os.Getenv("OIDC_CLIENT_SECRET"),
		oidcRedirectURL:   os.Getenv("OIDC_REDIRECT_URL"),
		tlsCertFile:       os.Getenv("TLS_CERT_FILE"),
		tlsKeyFile:        os.Getenv("TLS_KEY_FILE"),
		rostackBaseURL:    os.Getenv("ROSTACK_BASE_URL"),
		rostackToken:      os.Getenv("ROSTACK_TOKEN"),
		rostackAPIVersion: envOr("ROSTACK_API_VERSION", "2026-08-13.2"),
	}
	for name, value := range map[string]string{
		"COLLECT_TOKEN": cfg.collectToken, "MCP_TOKEN": cfg.mcpToken,
		"OIDC_ISSUER_URL": cfg.oidcIssuerURL, "OIDC_CLIENT_ID": cfg.oidcClientID,
		"OIDC_REDIRECT_URL": cfg.oidcRedirectURL,
		"ROSTACK_BASE_URL":  cfg.rostackBaseURL, "ROSTACK_TOKEN": cfg.rostackToken,
	} {
		if strings.TrimSpace(value) == "" {
			return config{}, fmt.Errorf("%s is required", name)
		}
	}
	if (cfg.tlsCertFile == "") != (cfg.tlsKeyFile == "") {
		return config{}, errors.New("TLS_CERT_FILE and TLS_KEY_FILE must be configured together")
	}
	return cfg, nil
}

func envOr(name, fallback string) string {
	if value := os.Getenv(name); value != "" {
		return value
	}
	return fallback
}

func splitCSV(value string) []string {
	if strings.TrimSpace(value) == "" {
		return nil
	}
	return strings.Split(value, ",")
}
