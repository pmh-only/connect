package rostack

import (
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"

	"connect/server/internal/store"
	"github.com/gorilla/websocket"
)

const (
	protocolVersion = "rostack_v1"
	resourceName    = "devices"
	readPermission  = "devices:read"
	subPermission   = "devices:subscribe"
)

type Config struct {
	BaseURL    string
	Token      string
	APIVersion string
}

type Server struct {
	store       *store.Store
	baseURL     *url.URL
	tokenHash   [32]byte
	apiVersion  string
	principalID string
	upgrader    websocket.Upgrader
}

func New(dataStore *store.Store, config Config) (*Server, error) {
	if dataStore == nil || config.Token == "" || config.APIVersion == "" {
		return nil, errors.New("rostack store, token, and API version are required")
	}
	baseURL, err := url.Parse(config.BaseURL)
	if err != nil || baseURL.Scheme != "https" || baseURL.Host == "" || baseURL.RawQuery != "" || baseURL.Fragment != "" {
		return nil, errors.New("rostack base URL must be an absolute HTTPS URL without a query or fragment")
	}
	baseURL.Path = strings.TrimSuffix(baseURL.Path, "/")
	tokenHash := sha256.Sum256([]byte(config.Token))
	return &Server{
		store: dataStore, baseURL: baseURL, tokenHash: tokenHash, apiVersion: config.APIVersion,
		principalID: "shared-token-" + base64.RawURLEncoding.EncodeToString(tokenHash[:12]),
		upgrader: websocket.Upgrader{
			HandshakeTimeout: 10 * time.Second,
			Subprotocols:     []string{"rostack.v1"},
			CheckOrigin:      func(*http.Request) bool { return true },
		},
	}, nil
}

func (s *Server) Register(mux *http.ServeMux) {
	mux.HandleFunc("GET /.well-known/rostack", s.discovery)
	mux.HandleFunc("GET /rostack/v1/schemas/device.json", s.deviceSchema)
	mux.HandleFunc("GET /rostack/v1/schemas/device-event.json", s.eventSchema)
	mux.HandleFunc("GET /rostack/v1/devices", s.listDevices)
	mux.HandleFunc("GET /rostack/v1/devices/{id}", s.getDevice)
	mux.HandleFunc("GET /rostack/v1/events", s.gateway)
}

func (s *Server) endpoint(path string) string {
	result := *s.baseURL
	result.Path = strings.TrimSuffix(s.baseURL.Path, "/") + path
	return result.String()
}

func (s *Server) websocketEndpoint() string {
	result := *s.baseURL
	result.Scheme = "wss"
	result.Path = strings.TrimSuffix(s.baseURL.Path, "/") + "/rostack/v1/events"
	return result.String()
}

func (s *Server) discovery(w http.ResponseWriter, _ *http.Request) {
	document := map[string]any{
		"protocol": map[string]any{"name": "rostack", "version": protocolVersion},
		"implementation": map[string]any{
			"id": "connect", "name": "Connect", "api_version": s.apiVersion,
		},
		"endpoints": map[string]any{
			"discovery": s.endpoint("/.well-known/rostack"),
			"json_api":  s.endpoint("/rostack/v1"),
			"websocket": s.websocketEndpoint(),
		},
		"authentication": map[string]any{
			"discovery_public": true,
			"methods": []any{map[string]any{
				"type": "shared_token", "http_authorization_scheme": "Rostack-Token", "provisioning": "out_of_band",
			}},
			"permissions": map[string]any{
				readPermission: "Read the latest device snapshots", subPermission: "Subscribe to device snapshot updates",
			},
		},
		"capabilities": map[string]any{
			"filter_operators": []string{}, "max_page_size": 100,
			"websocket": map[string]any{
				"event_encodings": []string{"json"}, "extensions": []string{},
				"authentication_timeout_ms": 10000, "heartbeat_interval_ms": 30000,
				"heartbeat_timeout_ms": 10000, "reconnect_min_delay_ms": 500,
				"reconnect_max_delay_ms": 30000, "discovery_refresh_after_ms": 300000,
			},
		},
		"resources": []any{map[string]any{
			"name": resourceName, "description": "Latest collected snapshot for each Connect device.",
			"collection_url":    s.endpoint("/rostack/v1/devices"),
			"item_url_template": s.endpoint("/rostack/v1/devices/{id}"),
			"representations": []any{map[string]any{
				"media_type": "application/json", "schema_url": s.endpoint("/rostack/v1/schemas/device.json"),
				"schema_dialect": "https://json-schema.org/draft/2020-12/schema",
			}},
			"events": []any{
				map[string]any{"name": "device.created", "schema_url": s.endpoint("/rostack/v1/schemas/device-event.json"), "schema_dialect": "https://json-schema.org/draft/2020-12/schema", "state_transition": "create", "tombstone": false},
				map[string]any{"name": "device.updated", "schema_url": s.endpoint("/rostack/v1/schemas/device-event.json"), "schema_dialect": "https://json-schema.org/draft/2020-12/schema", "state_transition": "update", "tombstone": false},
			},
			"event_filtering":  false,
			"filtering":        map[string]any{"filterable_fields": map[string]any{}, "sortable_fields": []string{}},
			"read_permissions": []string{readPermission}, "subscribe_permissions": []string{subPermission},
		}},
	}
	writeJSON(w, http.StatusOK, document)
}

func (s *Server) deviceSchema(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{
		"$schema": "https://json-schema.org/draft/2020-12/schema",
		"$id":     s.endpoint("/rostack/v1/schemas/device.json"),
		"title":   "Connect device snapshot", "type": "object", "required": []string{"deviceId", "collectedAt", "receivedAt"},
		"properties": map[string]any{
			"deviceId": map[string]any{"type": "string"}, "deviceName": map[string]any{"type": "string"},
			"collectedAt": map[string]any{"type": "integer"}, "receivedAt": map[string]any{"type": "integer"},
		},
		"additionalProperties": true,
	})
}

func (s *Server) eventSchema(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{
		"$schema": "https://json-schema.org/draft/2020-12/schema",
		"$id":     s.endpoint("/rostack/v1/schemas/device-event.json"), "title": "Connect device event data",
	})
}

func (s *Server) listDevices(w http.ResponseWriter, r *http.Request) {
	if !s.authorizeHTTP(w, r) {
		return
	}
	if !acceptsJSON(r.Header.Get("Accept")) {
		s.problem(w, http.StatusNotAcceptable, "Representation not acceptable", "devices are available as application/json")
		return
	}
	for _, unsupported := range []string{"filter", "sort", "fields"} {
		if r.URL.Query().Has(unsupported) {
			s.problem(w, http.StatusBadRequest, "Unsupported query parameter", unsupported+" is not supported for devices")
			return
		}
	}
	limit := 100
	if raw := r.URL.Query().Get("limit"); raw != "" {
		value, err := strconv.Atoi(raw)
		if err != nil || value < 1 || value > 100 {
			s.problem(w, http.StatusBadRequest, "Invalid limit", "limit must be between 1 and 100")
			return
		}
		limit = value
	}
	items, boundary := s.store.Snapshot()
	offset := 0
	if raw := r.URL.Query().Get("cursor"); raw != "" {
		var ok bool
		boundary, offset, ok = s.parsePageCursor(raw)
		if !ok {
			s.problem(w, http.StatusBadRequest, "Invalid cursor", "cursor is not valid for this collection")
			return
		}
		items, ok = s.store.SnapshotAt(boundary)
		if !ok {
			s.problem(w, http.StatusBadRequest, "Invalid cursor", "cursor is no longer available")
			return
		}
	}
	if offset > len(items) {
		s.problem(w, http.StatusBadRequest, "Invalid cursor", "cursor is not valid for this collection")
		return
	}
	end := min(offset+limit, len(items))
	var next any
	if end < len(items) {
		next = s.pageCursor(boundary, end)
	}
	s.successHeaders(w)
	writeJSON(w, http.StatusOK, map[string]any{
		"items": items[offset:end],
		"page":  map[string]any{"next_cursor": next, "has_more": end < len(items), "event_cursor": s.cursor(boundary)},
	})
}

func (s *Server) getDevice(w http.ResponseWriter, r *http.Request) {
	if !s.authorizeHTTP(w, r) {
		return
	}
	if !acceptsJSON(r.Header.Get("Accept")) {
		s.problem(w, http.StatusNotAcceptable, "Representation not acceptable", "devices are available as application/json")
		return
	}
	if r.URL.Query().Has("fields") {
		s.problem(w, http.StatusBadRequest, "Unsupported query parameter", "fields is not supported for devices")
		return
	}
	device, ok := s.store.Latest(r.PathValue("id"))
	if !ok {
		s.problem(w, http.StatusNotFound, "Device not found", "no device has the requested identifier")
		return
	}
	s.successHeaders(w)
	w.Header().Set("ETag", fmt.Sprintf(`"%d"`, device.ReceivedAt))
	writeJSON(w, http.StatusOK, device)
}

func (s *Server) authorizeHTTP(w http.ResponseWriter, r *http.Request) bool {
	if !s.validToken(r.Header.Get("Authorization"), "Rostack-Token ") {
		w.Header().Set("WWW-Authenticate", "Rostack-Token")
		s.problem(w, http.StatusUnauthorized, "Authentication required", "a valid shared token is required")
		return false
	}
	return true
}

func (s *Server) validToken(header, prefix string) bool {
	if !strings.HasPrefix(header, prefix) {
		return false
	}
	actual := sha256.Sum256([]byte(strings.TrimSpace(strings.TrimPrefix(header, prefix))))
	return subtle.ConstantTimeCompare(actual[:], s.tokenHash[:]) == 1
}

func (s *Server) successHeaders(w http.ResponseWriter) {
	w.Header().Set("X-Rostack-Protocol-Version", protocolVersion)
	w.Header().Set("X-Rostack-API-Version", s.apiVersion)
}

func (s *Server) problem(w http.ResponseWriter, status int, title, detail string) {
	w.Header().Set("Content-Type", "application/problem+json")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(map[string]any{"type": "about:blank", "title": title, "status": status, "detail": detail})
}

func writeJSON(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(value)
}

type clientMessage struct {
	Type           string          `json:"type"`
	Method         string          `json:"method"`
	Token          string          `json:"token"`
	SubscriptionID string          `json:"subscription_id"`
	Resource       string          `json:"resource"`
	EventTypes     []string        `json:"event_types"`
	Filter         json.RawMessage `json:"filter"`
	Cursor         string          `json:"cursor"`
	EventEncoding  string          `json:"event_encoding"`
	ID             string          `json:"id"`
}

type subscription struct {
	id              string
	eventTypes      map[string]bool
	sequence        uint64
	requestedCursor string
}

func (s *Server) gateway(w http.ResponseWriter, r *http.Request) {
	if !hasSubprotocol(r, "rostack.v1") {
		s.problem(w, http.StatusBadRequest, "WebSocket subprotocol required", "request rostack.v1")
		return
	}
	connection, err := s.upgrader.Upgrade(w, r, nil)
	if err != nil {
		return
	}
	defer connection.Close()
	connection.SetReadLimit(64 << 10)
	connection.SetReadDeadline(time.Now().Add(10 * time.Second))

	messages := make(chan clientMessage)
	readErrors := make(chan error, 1)
	done := make(chan struct{})
	defer close(done)
	go func() {
		defer close(messages)
		for {
			messageType, payload, readErr := connection.ReadMessage()
			if readErr != nil {
				select {
				case readErrors <- readErr:
				case <-done:
				}
				return
			}
			if messageType != websocket.TextMessage {
				select {
				case readErrors <- errors.New("messages must use text frames"):
				case <-done:
				}
				return
			}
			var message clientMessage
			if json.Unmarshal(payload, &message) != nil || message.Type == "" {
				select {
				case readErrors <- errors.New("invalid JSON message"):
				case <-done:
				}
				return
			}
			select {
			case messages <- message:
			case <-done:
				return
			}
		}
	}()

	first, ok := <-messages
	if !ok || first.Type != "authenticate" || first.Method != "shared_token" || !s.validToken("Rostack-Token "+first.Token, "Rostack-Token ") {
		s.close(connection, 4401, "authentication required")
		return
	}
	connection.SetReadDeadline(time.Time{})
	if !s.write(connection, map[string]any{
		"type": "authenticated", "method": "shared_token", "protocol_version": protocolVersion,
		"api_version": s.apiVersion, "principal_id": s.principalID,
	}) {
		return
	}

	wake, unsubscribe := s.store.Subscribe()
	defer unsubscribe()
	subscriptions := make(map[string]*subscription)
	for {
		select {
		case message, open := <-messages:
			if !open {
				select {
				case err := <-readErrors:
					if err != nil && !websocket.IsCloseError(err, websocket.CloseNormalClosure, websocket.CloseGoingAway) {
						s.close(connection, 4400, "invalid protocol message")
					}
				default:
				}
				return
			}
			if !s.handleMessage(connection, message, subscriptions) {
				return
			}
		case <-wake:
			for _, current := range subscriptions {
				if !s.deliverAfter(connection, current) {
					return
				}
			}
		case err := <-readErrors:
			if err != nil && !websocket.IsCloseError(err, websocket.CloseNormalClosure, websocket.CloseGoingAway) {
				s.close(connection, 4400, "invalid protocol message")
			}
			return
		}
	}
}

func (s *Server) handleMessage(connection *websocket.Conn, message clientMessage, subscriptions map[string]*subscription) bool {
	switch message.Type {
	case "authenticate":
		if message.Method != "shared_token" || !s.validToken("Rostack-Token "+message.Token, "Rostack-Token ") {
			return s.writeError(connection, "authentication_failed", "credential is invalid", true, "")
		}
		return s.write(connection, map[string]any{"type": "authenticated", "method": "shared_token", "protocol_version": protocolVersion, "api_version": s.apiVersion, "principal_id": s.principalID})
	case "ping":
		if message.ID == "" {
			return s.writeError(connection, "invalid_message", "ping id is required", false, "")
		}
		return s.write(connection, map[string]any{"type": "pong", "id": message.ID})
	case "pong":
		return true
	case "unsubscribe":
		delete(subscriptions, message.SubscriptionID)
		return true
	case "subscribe":
		return s.subscribe(connection, message, subscriptions)
	default:
		return s.writeError(connection, "invalid_message", "unsupported message type", false, message.SubscriptionID)
	}
}

func (s *Server) subscribe(connection *websocket.Conn, message clientMessage, subscriptions map[string]*subscription) bool {
	if message.SubscriptionID == "" || message.Resource != resourceName || len(message.Filter) > 0 || (message.EventEncoding != "" && message.EventEncoding != "json") {
		return s.writeError(connection, "invalid_subscription", "subscription definition is not supported", false, message.SubscriptionID)
	}
	eventTypes := map[string]bool{"device.created": true, "device.updated": true}
	if len(message.EventTypes) > 0 {
		eventTypes = make(map[string]bool, len(message.EventTypes))
		for _, eventType := range message.EventTypes {
			if eventType != "device.created" && eventType != "device.updated" {
				return s.writeError(connection, "invalid_subscription", "event type is not advertised", false, message.SubscriptionID)
			}
			eventTypes[eventType] = true
		}
	}
	if existing, exists := subscriptions[message.SubscriptionID]; exists {
		if existing.requestedCursor == message.Cursor && equalEventTypes(existing.eventTypes, eventTypes) {
			return s.write(connection, map[string]any{"type": "subscribed", "subscription_id": existing.id, "event_encoding": "json", "replaying": false})
		}
		return s.writeError(connection, "subscription_id_conflict", "subscription id is already active", false, message.SubscriptionID)
	}
	sequence := uint64(0)
	replaying := message.Cursor != ""
	if replaying {
		var ok bool
		sequence, ok = s.parseCursor(message.Cursor)
		if !ok {
			return s.writeError(connection, "cursor_scope_mismatch", "cursor is not valid for this principal and API version", false, message.SubscriptionID)
		}
		if _, ok := s.store.EventsAfter(sequence); !ok {
			return s.writeError(connection, "cursor_unavailable", "cursor is no longer available", false, message.SubscriptionID)
		}
	} else {
		_, sequence = s.store.Snapshot()
	}
	current := &subscription{id: message.SubscriptionID, eventTypes: eventTypes, sequence: sequence, requestedCursor: message.Cursor}
	subscriptions[current.id] = current
	if !s.write(connection, map[string]any{"type": "subscribed", "subscription_id": current.id, "event_encoding": "json", "replaying": replaying}) {
		return false
	}
	if replaying && !s.deliverAfter(connection, current) {
		return false
	}
	if replaying {
		return s.write(connection, map[string]any{"type": "replay_complete", "subscription_id": current.id})
	}
	return true
}

func (s *Server) deliverAfter(connection *websocket.Conn, subscription *subscription) bool {
	events, ok := s.store.EventsAfter(subscription.sequence)
	if !ok {
		return s.writeError(connection, "cursor_unavailable", "cursor is no longer available", false, subscription.id)
	}
	for _, event := range events {
		eventType := "device.updated"
		if event.Created {
			eventType = "device.created"
		}
		subscription.sequence = event.Sequence
		if !subscription.eventTypes[eventType] {
			continue
		}
		if !s.write(connection, map[string]any{
			"type": "event", "subscription_id": subscription.id,
			"event_id": fmt.Sprintf("connect-device-%d", event.Sequence), "cursor": s.cursor(event.Sequence),
			"occurred_at": time.UnixMilli(event.Collection.ReceivedAt).UTC().Format(time.RFC3339Nano),
			"event_type":  eventType, "resource_id": event.Collection.DeviceID,
			"resource_version": strconv.FormatInt(event.Collection.ReceivedAt, 10),
			"detail_url":       s.endpoint("/rostack/v1/devices/" + url.PathEscape(event.Collection.DeviceID)),
		}) {
			return false
		}
	}
	return true
}

func (s *Server) cursor(sequence uint64) string {
	payload := fmt.Sprintf("%s\n%s\n%s\n%d", s.apiVersion, resourceName, s.principalID, sequence)
	return base64.RawURLEncoding.EncodeToString([]byte(payload))
}

func (s *Server) pageCursor(boundary uint64, offset int) string {
	payload := fmt.Sprintf("page\n%s\n%s\n%d\n%d", s.apiVersion, resourceName, boundary, offset)
	return base64.RawURLEncoding.EncodeToString([]byte(payload))
}

func (s *Server) parsePageCursor(cursor string) (uint64, int, bool) {
	payload, err := base64.RawURLEncoding.DecodeString(cursor)
	if err != nil {
		return 0, 0, false
	}
	parts := strings.Split(string(payload), "\n")
	if len(parts) != 5 || parts[0] != "page" || parts[1] != s.apiVersion || parts[2] != resourceName {
		return 0, 0, false
	}
	boundary, boundaryErr := strconv.ParseUint(parts[3], 10, 64)
	offset, offsetErr := strconv.Atoi(parts[4])
	return boundary, offset, boundaryErr == nil && offsetErr == nil && offset >= 0
}

func (s *Server) parseCursor(cursor string) (uint64, bool) {
	payload, err := base64.RawURLEncoding.DecodeString(cursor)
	if err != nil {
		return 0, false
	}
	parts := strings.Split(string(payload), "\n")
	if len(parts) != 4 || parts[0] != s.apiVersion || parts[1] != resourceName || parts[2] != s.principalID {
		return 0, false
	}
	sequence, err := strconv.ParseUint(parts[3], 10, 64)
	return sequence, err == nil
}

func (s *Server) write(connection *websocket.Conn, value any) bool {
	connection.SetWriteDeadline(time.Now().Add(10 * time.Second))
	return connection.WriteJSON(value) == nil
}

func (s *Server) writeError(connection *websocket.Conn, code, message string, retryable bool, subscriptionID string) bool {
	value := map[string]any{"type": "error", "code": code, "message": message, "retryable": retryable}
	if subscriptionID != "" {
		value["subscription_id"] = subscriptionID
	}
	return s.write(connection, value)
}

func (s *Server) close(connection *websocket.Conn, code int, reason string) {
	connection.WriteControl(websocket.CloseMessage, websocket.FormatCloseMessage(code, reason), time.Now().Add(time.Second))
}

func hasSubprotocol(r *http.Request, expected string) bool {
	for _, value := range strings.Split(r.Header.Get("Sec-WebSocket-Protocol"), ",") {
		if strings.TrimSpace(value) == expected {
			return true
		}
	}
	return false
}

func equalEventTypes(left, right map[string]bool) bool {
	if len(left) != len(right) {
		return false
	}
	for eventType := range left {
		if !right[eventType] {
			return false
		}
	}
	return true
}

func acceptsJSON(header string) bool {
	if header == "" {
		return true
	}
	for _, mediaRange := range strings.Split(header, ",") {
		mediaType := strings.TrimSpace(strings.SplitN(mediaRange, ";", 2)[0])
		if mediaType == "*/*" || mediaType == "application/*" || mediaType == "application/json" {
			return true
		}
	}
	return false
}
