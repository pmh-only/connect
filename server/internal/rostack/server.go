package rostack

import (
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"slices"
	"strconv"
	"strings"
	"time"

	"connect/server/internal/store"
	"github.com/gorilla/websocket"
)

const (
	protocolVersion = "rostack_v1"
	readPermission  = "telemetry:read"
	subPermission   = "telemetry:subscribe"
	problemPrefix   = "https://spec.pmh.codes/problems/"
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
	for _, definition := range sortedResourceDefinitions() {
		mux.HandleFunc("GET /rostack/v1/schemas/"+definition.name+".json", func(w http.ResponseWriter, _ *http.Request) {
			writeJSON(w, http.StatusOK, resourceSchema(definition, s.endpoint("/rostack/v1/schemas/"+definition.name+".json")))
		})
	}
	mux.HandleFunc("GET /rostack/v1/{resource}", s.listResource)
	mux.HandleFunc("GET /rostack/v1/{resource}/{id}", s.getResource)
	mux.HandleFunc("GET /rostack/v1/events", s.gateway)
	mux.HandleFunc("/rostack/v1/{resource}", s.methodNotAllowed)
	mux.HandleFunc("/rostack/v1/{resource}/{id}", s.methodNotAllowed)
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
	resources := make([]any, 0, len(resourceCatalog))
	for _, definition := range sortedResourceDefinitions() {
		schemaURL := s.endpoint("/rostack/v1/schemas/" + definition.name + ".json")
		resources = append(resources, map[string]any{
			"name": definition.name, "description": definition.description,
			"collection_url":    s.endpoint("/rostack/v1/" + definition.name),
			"item_url_template": s.itemURLTemplate(definition.name),
			"representations": []any{map[string]any{
				"media_type": "application/json", "schema_url": schemaURL,
				"schema_dialect": "https://json-schema.org/draft/2020-12/schema",
			}},
			"events": []any{
				map[string]any{"name": definition.name + ".created", "schema_url": schemaURL, "schema_dialect": "https://json-schema.org/draft/2020-12/schema", "state_transition": "create", "tombstone": false},
				map[string]any{"name": definition.name + ".updated", "schema_url": schemaURL, "schema_dialect": "https://json-schema.org/draft/2020-12/schema", "state_transition": "update", "tombstone": false},
				map[string]any{"name": definition.name + ".deleted", "schema_url": schemaURL, "schema_dialect": "https://json-schema.org/draft/2020-12/schema", "state_transition": "delete", "tombstone": true},
			},
			"event_filtering":  false,
			"filtering":        map[string]any{"filterable_fields": map[string]any{}, "sortable_fields": []string{}},
			"read_permissions": []string{readPermission}, "subscribe_permissions": []string{subPermission},
		})
	}
	document := map[string]any{
		"protocol": map[string]any{"name": "rostack", "version": protocolVersion},
		"implementation": map[string]any{
			"id": "connect", "name": "Connect", "api_version": s.apiVersion,
			"documentation_url": s.endpoint("/"),
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
				readPermission: "Read telemetry from all devices", subPermission: "Subscribe to telemetry updates",
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
		"errors": map[string]any{
			"http": []any{
				map[string]any{"type": problemPrefix + "invalid-request", "operations": []string{"list", "get"}},
				map[string]any{"type": problemPrefix + "unsupported-filter", "operations": []string{"list"}},
				map[string]any{"type": problemPrefix + "invalid-sort", "operations": []string{"list"}},
				map[string]any{"type": problemPrefix + "invalid-fields", "operations": []string{"list", "get"}},
				map[string]any{"type": problemPrefix + "invalid-cursor", "operations": []string{"list"}},
				map[string]any{"type": problemPrefix + "authentication-required", "operations": []string{"list", "get"}},
				map[string]any{"type": problemPrefix + "resource-not-found", "operations": []string{"list", "get"}},
				map[string]any{"type": problemPrefix + "method-not-allowed", "operations": []string{"list", "get"}},
				map[string]any{"type": problemPrefix + "representation-not-acceptable", "operations": []string{"list", "get"}},
			},
			"websocket": []any{
				map[string]any{"code": "invalid_message", "operations": []string{"authenticate", "subscribe", "subscription", "session"}},
				map[string]any{"code": "authentication_failed", "operations": []string{"authenticate"}},
				map[string]any{"code": "reauthentication_identity_mismatch", "operations": []string{"authenticate"}},
				map[string]any{"code": "resource_not_found", "operations": []string{"subscribe"}},
				map[string]any{"code": "unsupported_event_type", "operations": []string{"subscribe"}},
				map[string]any{"code": "unsupported_filter", "operations": []string{"subscribe"}},
				map[string]any{"code": "unsupported_encoding", "operations": []string{"subscribe"}},
				map[string]any{"code": "subscription_id_conflict", "operations": []string{"subscribe"}},
				map[string]any{"code": "cursor_scope_mismatch", "operations": []string{"subscribe"}},
				map[string]any{"code": "cursor_unavailable", "operations": []string{"subscribe", "subscription"}},
			},
		},
		"resources": resources,
	}
	w.Header().Set("Cache-Control", "public, max-age=300")
	w.Header().Set("ETag", fmt.Sprintf(`"rostack-%s"`, s.apiVersion))
	writeJSON(w, http.StatusOK, document)
}

func (s *Server) itemURLTemplate(resource string) string {
	return strings.TrimSuffix(s.baseURL.String(), "/") + "/rostack/v1/" + resource + "/{id}"
}

func (s *Server) itemURL(resource, id string) string {
	return strings.Replace(s.itemURLTemplate(resource), "{id}", url.PathEscape(id), 1)
}

func (s *Server) listResource(w http.ResponseWriter, r *http.Request) {
	definition, ok := resourceCatalog[r.PathValue("resource")]
	if !ok {
		s.problem(w, "resource-not-found", "resource is not advertised")
		return
	}
	if !s.authorizeHTTP(w, r) {
		return
	}
	if !acceptsJSON(r.Header.Get("Accept")) {
		s.problem(w, "representation-not-acceptable", "resource is available as application/json")
		return
	}
	if r.URL.Query().Has("filter") {
		s.problem(w, "unsupported-filter", "filtering is not supported for this resource")
		return
	}
	if r.URL.Query().Has("sort") {
		s.problem(w, "invalid-sort", "sorting is not supported for this resource")
		return
	}
	if r.URL.Query().Has("fields") {
		s.problem(w, "invalid-fields", "field projection is not supported for devices")
		return
	}
	for name := range r.URL.Query() {
		if name != "filter" && name != "sort" && name != "fields" && name != "limit" && name != "cursor" {
			s.problem(w, "invalid-request", "query parameter is not defined by rostack_v1")
			return
		}
	}
	limit := 100
	if raw := r.URL.Query().Get("limit"); raw != "" {
		value, err := strconv.Atoi(raw)
		if err != nil || value < 1 || value > 100 {
			s.problem(w, "invalid-request", "limit must be between 1 and 100")
			return
		}
		limit = value
	}
	collections, boundary := s.store.Snapshot()
	items := resourceItems(definition, collections)
	offset := 0
	if raw := r.URL.Query().Get("cursor"); raw != "" {
		var ok bool
		boundary, offset, ok = s.parsePageCursor(definition.name, raw, limit)
		if !ok {
			s.problem(w, "invalid-cursor", "cursor is not valid for this collection")
			return
		}
		collections, ok = s.store.SnapshotAt(boundary)
		if !ok {
			s.problem(w, "invalid-cursor", "cursor is no longer available")
			return
		}
		items = resourceItems(definition, collections)
	}
	if offset > len(items) {
		s.problem(w, "invalid-cursor", "cursor is not valid for this collection")
		return
	}
	end := min(offset+limit, len(items))
	var next any
	if end < len(items) {
		next = s.pageCursor(definition.name, boundary, end, limit)
	}
	s.successHeaders(w)
	writeJSON(w, http.StatusOK, map[string]any{
		"items": items[offset:end],
		"page":  map[string]any{"next_cursor": next, "has_more": end < len(items), "event_cursor": s.cursor(definition.name, boundary)},
	})
}

func (s *Server) getResource(w http.ResponseWriter, r *http.Request) {
	definition, ok := resourceCatalog[r.PathValue("resource")]
	if !ok {
		s.problem(w, "resource-not-found", "resource is not advertised")
		return
	}
	if !s.authorizeHTTP(w, r) {
		return
	}
	if !acceptsJSON(r.Header.Get("Accept")) {
		s.problem(w, "representation-not-acceptable", "resource is available as application/json")
		return
	}
	if r.URL.Query().Has("fields") {
		s.problem(w, "invalid-fields", "field projection is not supported for devices")
		return
	}
	for name := range r.URL.Query() {
		if name != "fields" {
			s.problem(w, "invalid-request", "query parameter is not defined for an item request")
			return
		}
	}
	collection, ok := s.store.Latest(r.PathValue("id"))
	if !ok {
		s.problem(w, "resource-not-found", "no device has the requested identifier")
		return
	}
	item, present := definition.item(collection)
	if !present {
		s.problem(w, "resource-not-found", "device has no value for this resource")
		return
	}
	s.successHeaders(w)
	w.Header().Set("ETag", fmt.Sprintf(`"%d"`, collection.ReceivedAt))
	writeJSON(w, http.StatusOK, item)
}

func (s *Server) authorizeHTTP(w http.ResponseWriter, r *http.Request) bool {
	if !s.validAuthorization(r.Header.Get("Authorization")) {
		w.Header().Set("WWW-Authenticate", "Rostack-Token")
		s.problem(w, "authentication-required", "a valid shared token is required")
		return false
	}
	return true
}

func (s *Server) validAuthorization(header string) bool {
	scheme, token, found := strings.Cut(strings.TrimSpace(header), " ")
	if !found || !strings.EqualFold(scheme, "Rostack-Token") {
		return false
	}
	return s.validToken(strings.TrimSpace(token))
}

func (s *Server) validToken(token string) bool {
	actual := sha256.Sum256([]byte(token))
	return subtle.ConstantTimeCompare(actual[:], s.tokenHash[:]) == 1
}

func (s *Server) successHeaders(w http.ResponseWriter) {
	w.Header().Set("X-Rostack-Protocol-Version", protocolVersion)
	w.Header().Set("X-Rostack-API-Version", s.apiVersion)
}

func (s *Server) problem(w http.ResponseWriter, code, detail string) {
	status, title := problemDefinition(code)
	w.Header().Set("Content-Type", "application/problem+json")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(map[string]any{"type": problemPrefix + code, "title": title, "status": status, "detail": detail})
}

func problemDefinition(code string) (int, string) {
	switch code {
	case "invalid-request":
		return http.StatusBadRequest, "Invalid request"
	case "unsupported-filter":
		return http.StatusBadRequest, "Unsupported filter"
	case "invalid-sort":
		return http.StatusBadRequest, "Invalid sort"
	case "invalid-fields":
		return http.StatusBadRequest, "Invalid fields"
	case "invalid-cursor":
		return http.StatusBadRequest, "Invalid cursor"
	case "authentication-required":
		return http.StatusUnauthorized, "Authentication required"
	case "resource-not-found":
		return http.StatusNotFound, "Resource not found"
	case "method-not-allowed":
		return http.StatusMethodNotAllowed, "Method not allowed"
	case "representation-not-acceptable":
		return http.StatusNotAcceptable, "Representation not acceptable"
	default:
		panic("unknown rostack problem: " + code)
	}
}

func (s *Server) methodNotAllowed(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Allow", "GET")
	s.problem(w, "method-not-allowed", r.Method+" is not a permitted read-only operation")
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
	EventTypes     *[]string       `json:"event_types"`
	Filter         json.RawMessage `json:"filter"`
	Cursor         string          `json:"cursor"`
	EventEncoding  string          `json:"event_encoding"`
	ID             string          `json:"id"`
}

func decodeClientMessage(payload []byte) (clientMessage, error) {
	var envelope map[string]json.RawMessage
	if err := json.Unmarshal(payload, &envelope); err != nil {
		return clientMessage{}, err
	}
	var messageType string
	if err := json.Unmarshal(envelope["type"], &messageType); err != nil || messageType == "" {
		return clientMessage{}, errors.New("message type is required")
	}
	allowed := map[string][]string{
		"authenticate": {"type", "method", "token"},
		"subscribe":    {"type", "subscription_id", "resource", "event_types", "filter", "cursor", "event_encoding"},
		"unsubscribe":  {"type", "subscription_id"},
		"ping":         {"type", "id"},
		"pong":         {"type", "id"},
	}[messageType]
	if allowed == nil {
		return clientMessage{}, errors.New("unsupported client message type")
	}
	for field := range envelope {
		if !contains(allowed, field) {
			return clientMessage{}, errors.New("field is not allowed for message type")
		}
	}
	decoder := json.NewDecoder(strings.NewReader(string(payload)))
	decoder.DisallowUnknownFields()
	var message clientMessage
	if err := decoder.Decode(&message); err != nil {
		return clientMessage{}, err
	}
	if _, present := envelope["event_types"]; present && message.EventTypes == nil {
		return clientMessage{}, errors.New("event_types must be an array")
	}
	if err := decoder.Decode(&struct{}{}); !errors.Is(err, io.EOF) {
		return clientMessage{}, errors.New("message must contain one JSON value")
	}
	if err := validateClientMessage(message); err != nil {
		return clientMessage{}, err
	}
	return message, nil
}

func contains(values []string, target string) bool {
	return slices.Contains(values, target)
}

func validateClientMessage(message clientMessage) error {
	switch message.Type {
	case "authenticate":
		if message.Method == "" || message.Token == "" || message.SubscriptionID != "" || message.Resource != "" || message.EventTypes != nil || len(message.Filter) > 0 || message.Cursor != "" || message.EventEncoding != "" || message.ID != "" {
			return errors.New("invalid authenticate message")
		}
	case "subscribe":
		if message.SubscriptionID == "" || message.Resource == "" || message.Method != "" || message.Token != "" || message.ID != "" {
			return errors.New("invalid subscribe message")
		}
	case "unsubscribe":
		if message.SubscriptionID == "" || message.Method != "" || message.Token != "" || message.Resource != "" || message.EventTypes != nil || len(message.Filter) > 0 || message.Cursor != "" || message.EventEncoding != "" || message.ID != "" {
			return errors.New("invalid unsubscribe message")
		}
	case "ping", "pong":
		if message.ID == "" || message.Method != "" || message.Token != "" || message.SubscriptionID != "" || message.Resource != "" || message.EventTypes != nil || len(message.Filter) > 0 || message.Cursor != "" || message.EventEncoding != "" {
			return errors.New("invalid heartbeat message")
		}
	default:
		return errors.New("unsupported client message type")
	}
	return nil
}

func validFilterSyntax(raw json.RawMessage) bool {
	var expression map[string]json.RawMessage
	if json.Unmarshal(raw, &expression) != nil || len(expression) == 0 {
		return false
	}
	for field, value := range expression {
		switch field {
		case "$and", "$or":
			var children []json.RawMessage
			if json.Unmarshal(value, &children) != nil || len(children) == 0 {
				return false
			}
			for _, child := range children {
				if !validFilterSyntax(child) {
					return false
				}
			}
		case "$not":
			if !validFilterSyntax(value) {
				return false
			}
		default:
			if !strings.HasPrefix(field, "/") || !validFieldPredicate(value) {
				return false
			}
		}
	}
	return true
}

func validFieldPredicate(raw json.RawMessage) bool {
	var predicate map[string]json.RawMessage
	if json.Unmarshal(raw, &predicate) != nil || len(predicate) == 0 {
		return false
	}
	for operator, operand := range predicate {
		switch operator {
		case "in", "nin":
			var values []json.RawMessage
			if json.Unmarshal(operand, &values) != nil {
				return false
			}
			for _, value := range values {
				if !jsonScalar(value) {
					return false
				}
			}
		case "starts_with", "ends_with":
			var value string
			if json.Unmarshal(operand, &value) != nil {
				return false
			}
		case "exists":
			var value bool
			if json.Unmarshal(operand, &value) != nil {
				return false
			}
		case "eq", "ne", "gt", "gte", "lt", "lte", "contains":
			if !jsonValue(operand) {
				return false
			}
		default:
			return false
		}
	}
	return true
}

func jsonValue(raw json.RawMessage) bool {
	if jsonScalar(raw) {
		return true
	}
	var values []json.RawMessage
	if json.Unmarshal(raw, &values) != nil {
		return false
	}
	for _, value := range values {
		if !jsonScalar(value) {
			return false
		}
	}
	return true
}

func jsonScalar(raw json.RawMessage) bool {
	var value any
	if json.Unmarshal(raw, &value) != nil {
		return false
	}
	switch value.(type) {
	case nil, bool, float64, string:
		return true
	default:
		return false
	}
}

type subscription struct {
	id              string
	resource        string
	eventTypes      map[string]bool
	sequence        uint64
	requestedCursor string
}

func (s *Server) gateway(w http.ResponseWriter, r *http.Request) {
	if !hasSubprotocol(r, "rostack.v1") {
		http.Error(w, "WebSocket subprotocol rostack.v1 is required", http.StatusBadRequest)
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
			message, decodeErr := decodeClientMessage(payload)
			if decodeErr != nil {
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

	var first clientMessage
	select {
	case message, open := <-messages:
		if !open {
			s.close(connection, 4400, "invalid protocol message")
			return
		}
		first = message
	case <-time.After(10 * time.Second):
		s.close(connection, 4408, "authentication timeout")
		return
	case <-readErrors:
		s.close(connection, 4400, "invalid protocol message")
		return
	}
	if first.Type != "authenticate" || first.Method != "shared_token" || !s.validToken(first.Token) {
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
	heartbeat := time.NewTicker(30 * time.Second)
	defer heartbeat.Stop()
	lastInbound := time.Now()
	var heartbeatTimeout *time.Timer
	var heartbeatExpired <-chan time.Time
	var outstandingPing string
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
			lastInbound = time.Now()
			if message.Type == "pong" && message.ID == outstandingPing {
				if heartbeatTimeout != nil {
					heartbeatTimeout.Stop()
				}
				heartbeatExpired = nil
				outstandingPing = ""
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
		case <-heartbeat.C:
			if outstandingPing == "" && time.Since(lastInbound) >= 30*time.Second {
				outstandingPing = strconv.FormatInt(time.Now().UnixNano(), 36)
				if !s.write(connection, map[string]any{"type": "ping", "id": outstandingPing}) {
					return
				}
				heartbeatTimeout = time.NewTimer(10 * time.Second)
				heartbeatExpired = heartbeatTimeout.C
			}
		case <-heartbeatExpired:
			s.close(connection, 4408, "heartbeat timeout")
			return
		}
	}
}

func (s *Server) handleMessage(connection *websocket.Conn, message clientMessage, subscriptions map[string]*subscription) bool {
	switch message.Type {
	case "authenticate":
		if message.Method != "shared_token" {
			return s.writeError(connection, "reauthentication_identity_mismatch", "authentication method cannot change on an active connection", false, "")
		}
		if !s.validToken(message.Token) {
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
	if message.SubscriptionID == "" {
		return s.writeError(connection, "invalid_message", "subscription_id is required", false, "")
	}
	definition, advertised := resourceCatalog[message.Resource]
	if !advertised {
		return s.writeError(connection, "resource_not_found", "resource is not advertised", false, message.SubscriptionID)
	}
	if len(message.Filter) > 0 {
		if !validFilterSyntax(message.Filter) {
			return s.writeError(connection, "invalid_message", "filter does not conform to the filter schema", false, "")
		}
		return s.writeError(connection, "unsupported_filter", "event filtering is not advertised", false, message.SubscriptionID)
	}
	if message.EventEncoding != "" && message.EventEncoding != "json" {
		return s.writeError(connection, "unsupported_encoding", "event encoding is not advertised", false, message.SubscriptionID)
	}
	eventTypes := map[string]bool{
		definition.name + ".created": true,
		definition.name + ".updated": true,
		definition.name + ".deleted": true,
	}
	if message.EventTypes != nil {
		if len(*message.EventTypes) == 0 {
			return s.writeError(connection, "invalid_message", "event_types must not be empty when present", false, "")
		}
		eventTypes = make(map[string]bool, len(*message.EventTypes))
		for _, eventType := range *message.EventTypes {
			if eventType != definition.name+".created" && eventType != definition.name+".updated" && eventType != definition.name+".deleted" {
				return s.writeError(connection, "unsupported_event_type", "event type is not advertised", false, message.SubscriptionID)
			}
			if eventTypes[eventType] {
				return s.writeError(connection, "invalid_message", "event_types must contain unique values", false, "")
			}
			eventTypes[eventType] = true
		}
	}
	if existing, exists := subscriptions[message.SubscriptionID]; exists {
		if existing.resource == definition.name && existing.requestedCursor == message.Cursor && equalEventTypes(existing.eventTypes, eventTypes) {
			return s.write(connection, map[string]any{"type": "subscribed", "subscription_id": existing.id, "event_encoding": "json", "replaying": false})
		}
		return s.writeError(connection, "subscription_id_conflict", "subscription id is already active", false, message.SubscriptionID)
	}
	sequence := uint64(0)
	replaying := message.Cursor != ""
	if replaying {
		var cursorError string
		sequence, cursorError = s.parseCursor(definition.name, message.Cursor)
		if cursorError != "" {
			return s.writeError(connection, cursorError, "cursor cannot be used for this subscription", false, message.SubscriptionID)
		}
		if _, ok := s.store.EventsAfter(sequence); !ok {
			return s.writeError(connection, "cursor_unavailable", "cursor is no longer available", false, message.SubscriptionID)
		}
	} else {
		_, sequence = s.store.Snapshot()
	}
	current := &subscription{id: message.SubscriptionID, resource: definition.name, eventTypes: eventTypes, sequence: sequence, requestedCursor: message.Cursor}
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
		subscription.sequence = event.Sequence
		definition := resourceCatalog[subscription.resource]
		item, present := definition.item(event.Collection)
		_, previouslyPresent := definition.item(event.Previous)
		if !event.HadPrevious {
			previouslyPresent = false
		}
		if !present && !previouslyPresent {
			continue
		}
		eventType := definition.name + ".updated"
		if !previouslyPresent {
			eventType = definition.name + ".created"
		} else if !present {
			eventType = definition.name + ".deleted"
		}
		if !subscription.eventTypes[eventType] {
			continue
		}
		resourceVersion := event.Collection.ReceivedAt
		if !present {
			item, _ = definition.item(event.Previous)
			resourceVersion = event.Previous.ReceivedAt
		}
		if !s.write(connection, map[string]any{
			"type": "event", "subscription_id": subscription.id,
			"event_id": fmt.Sprintf("connect-%s-%d", definition.name, event.Sequence), "cursor": s.cursor(definition.name, event.Sequence),
			"occurred_at": time.UnixMilli(event.Collection.ReceivedAt).UTC().Format(time.RFC3339Nano),
			"event_type":  eventType, "resource_id": event.Collection.DeviceID,
			"resource_version": strconv.FormatInt(resourceVersion, 10),
			"detail_url":       s.itemURL(definition.name, event.Collection.DeviceID),
			"data":             item,
		}) {
			return false
		}
	}
	return true
}

func (s *Server) cursor(resource string, sequence uint64) string {
	payload := fmt.Sprintf("connect\n%s\n%s\n%s\n%d", s.apiVersion, resource, s.principalID, sequence)
	return base64.RawURLEncoding.EncodeToString([]byte(payload))
}

func (s *Server) pageCursor(resource string, boundary uint64, offset, limit int) string {
	payload := fmt.Sprintf("page\nconnect\n%s\n%s\n%d\n%d\n%d", s.apiVersion, resource, boundary, offset, limit)
	return base64.RawURLEncoding.EncodeToString([]byte(payload))
}

func (s *Server) parsePageCursor(resource, cursor string, limit int) (uint64, int, bool) {
	payload, err := base64.RawURLEncoding.DecodeString(cursor)
	if err != nil {
		return 0, 0, false
	}
	parts := strings.Split(string(payload), "\n")
	if len(parts) != 7 || parts[0] != "page" || parts[1] != "connect" || parts[2] != s.apiVersion || parts[3] != resource || parts[6] != strconv.Itoa(limit) {
		return 0, 0, false
	}
	boundary, boundaryErr := strconv.ParseUint(parts[4], 10, 64)
	offset, offsetErr := strconv.Atoi(parts[5])
	return boundary, offset, boundaryErr == nil && offsetErr == nil && offset >= 0
}

func (s *Server) parseCursor(resource, cursor string) (uint64, string) {
	payload, err := base64.RawURLEncoding.DecodeString(cursor)
	if err != nil {
		return 0, "cursor_unavailable"
	}
	parts := strings.Split(string(payload), "\n")
	if len(parts) != 5 || parts[0] != "connect" {
		return 0, "cursor_unavailable"
	}
	sequence, err := strconv.ParseUint(parts[4], 10, 64)
	if err != nil {
		return 0, "cursor_unavailable"
	}
	if parts[1] != s.apiVersion || parts[2] != resource || parts[3] != s.principalID {
		return 0, "cursor_scope_mismatch"
	}
	return sequence, ""
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
		parts := strings.Split(mediaRange, ";")
		mediaType := strings.TrimSpace(parts[0])
		quality := 1.0
		for _, parameter := range parts[1:] {
			name, value, found := strings.Cut(strings.TrimSpace(parameter), "=")
			if found && strings.EqualFold(name, "q") {
				quality, _ = strconv.ParseFloat(value, 64)
			}
		}
		if quality > 0 && (mediaType == "*/*" || mediaType == "application/*" || mediaType == "application/json") {
			return true
		}
	}
	return false
}
