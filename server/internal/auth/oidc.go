package auth

import (
	"context"
	"crypto/rand"
	"crypto/subtle"
	"encoding/base64"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"sync"
	"time"

	"github.com/coreos/go-oidc/v3/oidc"
	"golang.org/x/oauth2"
)

const (
	transactionTTL = 10 * time.Minute
	sessionTTL     = 12 * time.Hour
)

type Config struct {
	ClientID     string
	ClientSecret string
	RedirectURL  string
}

type User struct {
	Subject string
	Name    string
	Email   string
}

type transaction struct {
	BrowserID    string
	Nonce        string
	PKCEVerifier string
	ExpiresAt    time.Time
}

type session struct {
	User      User
	CSRFToken string
	ExpiresAt time.Time
}

type Auth struct {
	oauthConfig oauth2.Config
	verifier    *oidc.IDTokenVerifier
	clientID    string
	secure      bool

	mu           sync.Mutex
	transactions map[string]transaction
	sessions     map[string]session
}

func New(provider *oidc.Provider, config Config) (*Auth, error) {
	redirectURL, err := url.Parse(config.RedirectURL)
	if err != nil || redirectURL.Scheme == "" || redirectURL.Host == "" {
		return nil, errors.New("OIDC redirect URL must be an absolute URL")
	}
	if redirectURL.Path != "/oidc/callback" || redirectURL.RawQuery != "" || redirectURL.Fragment != "" {
		return nil, errors.New("OIDC redirect URL must use the /oidc/callback path without a query or fragment")
	}
	if config.ClientID == "" {
		return nil, errors.New("OIDC client ID is required")
	}
	return &Auth{
		oauthConfig: oauth2.Config{
			ClientID:     config.ClientID,
			ClientSecret: config.ClientSecret,
			Endpoint:     provider.Endpoint(),
			RedirectURL:  config.RedirectURL,
			Scopes:       []string{oidc.ScopeOpenID, "profile", "email"},
		},
		verifier:     provider.Verifier(&oidc.Config{ClientID: config.ClientID}),
		clientID:     config.ClientID,
		secure:       redirectURL.Scheme == "https",
		transactions: make(map[string]transaction),
		sessions:     make(map[string]session),
	}, nil
}

func (a *Auth) Login(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		w.Header().Set("Allow", http.MethodGet)
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	state, err := randomToken()
	if err != nil {
		http.Error(w, "could not start login", http.StatusInternalServerError)
		return
	}
	nonce, err := randomToken()
	if err != nil {
		http.Error(w, "could not start login", http.StatusInternalServerError)
		return
	}
	browserID, err := randomToken()
	if err != nil {
		http.Error(w, "could not start login", http.StatusInternalServerError)
		return
	}
	pkceVerifier := oauth2.GenerateVerifier()

	a.mu.Lock()
	a.cleanupLocked(time.Now())
	a.transactions[state] = transaction{
		BrowserID:    browserID,
		Nonce:        nonce,
		PKCEVerifier: pkceVerifier,
		ExpiresAt:    time.Now().Add(transactionTTL),
	}
	a.mu.Unlock()
	a.setCookie(w, "connect_pre_auth", browserID, time.Now().Add(transactionTTL))
	http.Redirect(
		w,
		r,
		a.oauthConfig.AuthCodeURL(
			state,
			oidc.Nonce(nonce),
			oauth2.S256ChallengeOption(pkceVerifier),
		),
		http.StatusFound,
	)
}

func (a *Auth) Callback(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cache-Control", "no-store")
	if r.Method != http.MethodGet {
		w.Header().Set("Allow", http.MethodGet)
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	state := r.URL.Query().Get("state")
	preAuth, err := r.Cookie("connect_pre_auth")
	if err != nil || state == "" {
		http.Error(w, "invalid login transaction", http.StatusBadRequest)
		return
	}

	a.mu.Lock()
	tx, ok := a.transactions[state]
	if ok {
		delete(a.transactions, state)
	}
	a.mu.Unlock()
	if !ok || time.Now().After(tx.ExpiresAt) || !constantTimeEqual(preAuth.Value, tx.BrowserID) {
		http.Error(w, "expired or invalid login transaction", http.StatusBadRequest)
		return
	}
	if providerError := r.URL.Query().Get("error"); providerError != "" {
		http.Error(w, "identity provider rejected login", http.StatusUnauthorized)
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), 15*time.Second)
	defer cancel()
	token, err := a.oauthConfig.Exchange(ctx, r.URL.Query().Get("code"), oauth2.VerifierOption(tx.PKCEVerifier))
	if err != nil {
		http.Error(w, "OIDC code exchange failed", http.StatusUnauthorized)
		return
	}
	rawIDToken, ok := token.Extra("id_token").(string)
	if !ok {
		http.Error(w, "OIDC response did not contain an ID token", http.StatusUnauthorized)
		return
	}
	idToken, err := a.verifier.Verify(ctx, rawIDToken)
	if err != nil || !constantTimeEqual(idToken.Nonce, tx.Nonce) {
		http.Error(w, "OIDC ID token verification failed", http.StatusUnauthorized)
		return
	}
	var claims struct {
		Subject         string `json:"sub"`
		Name            string `json:"name"`
		Email           string `json:"email"`
		AuthorizedParty string `json:"azp"`
	}
	if err := idToken.Claims(&claims); err != nil || claims.Subject == "" ||
		(claims.AuthorizedParty != "" && claims.AuthorizedParty != a.clientID) {
		http.Error(w, "OIDC identity claims are invalid", http.StatusUnauthorized)
		return
	}

	sessionID, err := randomToken()
	if err != nil {
		http.Error(w, "could not create session", http.StatusInternalServerError)
		return
	}
	csrfToken, err := randomToken()
	if err != nil {
		http.Error(w, "could not create session", http.StatusInternalServerError)
		return
	}
	expiresAt := time.Now().Add(sessionTTL)
	a.mu.Lock()
	a.sessions[sessionID] = session{
		User:      User{Subject: claims.Subject, Name: claims.Name, Email: claims.Email},
		CSRFToken: csrfToken,
		ExpiresAt: expiresAt,
	}
	a.mu.Unlock()
	a.setCookie(w, "connect_session", sessionID, expiresAt)
	a.clearCookie(w, "connect_pre_auth")
	http.Redirect(w, r, "/", http.StatusFound)
}

func (a *Auth) Require(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _, ok := a.sessionForRequest(r)
		if !ok {
			http.Redirect(w, r, "/login", http.StatusFound)
			return
		}
		next.ServeHTTP(w, r)
	})
}

func (a *Auth) Current(r *http.Request) (User, string, bool) {
	_, session, ok := a.sessionForRequest(r)
	return session.User, session.CSRFToken, ok
}

func (a *Auth) Logout(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		w.Header().Set("Allow", http.MethodPost)
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	sessionID, session, ok := a.sessionForRequest(r)
	if !ok || !constantTimeEqual(r.FormValue("csrf_token"), session.CSRFToken) {
		http.Error(w, "invalid logout request", http.StatusForbidden)
		return
	}
	a.mu.Lock()
	delete(a.sessions, sessionID)
	a.mu.Unlock()
	a.clearCookie(w, "connect_session")
	http.Redirect(w, r, "/login", http.StatusSeeOther)
}

func (a *Auth) sessionForRequest(r *http.Request) (string, session, bool) {
	cookie, err := r.Cookie("connect_session")
	if err != nil {
		return "", session{}, false
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	a.cleanupLocked(time.Now())
	current, ok := a.sessions[cookie.Value]
	return cookie.Value, current, ok
}

func (a *Auth) cleanupLocked(now time.Time) {
	for state, tx := range a.transactions {
		if now.After(tx.ExpiresAt) {
			delete(a.transactions, state)
		}
	}
	for id, session := range a.sessions {
		if now.After(session.ExpiresAt) {
			delete(a.sessions, id)
		}
	}
}

func (a *Auth) setCookie(w http.ResponseWriter, name, value string, expires time.Time) {
	http.SetCookie(w, &http.Cookie{
		Name:     name,
		Value:    value,
		Path:     "/",
		Expires:  expires,
		MaxAge:   int(time.Until(expires).Seconds()),
		Secure:   a.secure,
		HttpOnly: true,
		SameSite: http.SameSiteLaxMode,
	})
}

func (a *Auth) clearCookie(w http.ResponseWriter, name string) {
	http.SetCookie(w, &http.Cookie{
		Name:     name,
		Path:     "/",
		Expires:  time.Unix(1, 0),
		MaxAge:   -1,
		Secure:   a.secure,
		HttpOnly: true,
		SameSite: http.SameSiteLaxMode,
	})
}

func randomToken() (string, error) {
	value := make([]byte, 32)
	if _, err := rand.Read(value); err != nil {
		return "", fmt.Errorf("generate random token: %w", err)
	}
	return base64.RawURLEncoding.EncodeToString(value), nil
}

func constantTimeEqual(left, right string) bool {
	if len(left) != len(right) {
		return false
	}
	return subtle.ConstantTimeCompare([]byte(left), []byte(right)) == 1
}
