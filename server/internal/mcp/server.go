package mcp

import (
	"crypto/sha256"
	"crypto/subtle"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strings"

	"connect/server/internal/store"
)

const (
	protocolVersion = "2025-11-25"
	maxRequestBytes = 1 << 20
)

type Server struct {
	store          *store.Store
	token          string
	allowedOrigins map[string]struct{}
}

type request struct {
	JSONRPC string          `json:"jsonrpc"`
	ID      json.RawMessage `json:"id"`
	Method  string          `json:"method"`
	Params  json.RawMessage `json:"params"`
}

type response struct {
	JSONRPC string          `json:"jsonrpc"`
	ID      json.RawMessage `json:"id,omitempty"`
	Result  any             `json:"result,omitempty"`
	Error   *rpcError       `json:"error,omitempty"`
}

type rpcError struct {
	Code    int    `json:"code"`
	Message string `json:"message"`
}

func New(dataStore *store.Store, token string, allowedOrigins []string) *Server {
	origins := make(map[string]struct{}, len(allowedOrigins))
	for _, origin := range allowedOrigins {
		if trimmed := strings.TrimSpace(origin); trimmed != "" {
			origins[trimmed] = struct{}{}
		}
	}
	return &Server{store: dataStore, token: token, allowedOrigins: origins}
}

func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "text/plain; charset=utf-8")
		w.Write([]byte("ok\n"))
	})
	mux.HandleFunc("/mcp", s.handle)
	return mux
}

func (s *Server) handle(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cache-Control", "no-store")
	if r.Method != http.MethodPost {
		w.Header().Set("Allow", http.MethodPost)
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if origin := r.Header.Get("Origin"); origin != "" {
		if _, allowed := s.allowedOrigins[origin]; !allowed {
			http.Error(w, "origin not allowed", http.StatusForbidden)
			return
		}
	}
	if !validBearer(r.Header.Get("Authorization"), s.token) {
		w.Header().Set("WWW-Authenticate", "Bearer")
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	if !strings.HasPrefix(strings.ToLower(r.Header.Get("Content-Type")), "application/json") {
		http.Error(w, "content type must be application/json", http.StatusUnsupportedMediaType)
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, maxRequestBytes)
	decoder := json.NewDecoder(r.Body)
	decoder.UseNumber()
	var request request
	if err := decoder.Decode(&request); err != nil {
		s.writeError(w, nil, -32700, "Parse error")
		return
	}
	var extra any
	if err := decoder.Decode(&extra); !errors.Is(err, io.EOF) {
		s.writeError(w, request.ID, -32600, "Invalid request")
		return
	}
	if request.JSONRPC != "2.0" || request.Method == "" {
		s.writeError(w, request.ID, -32600, "Invalid request")
		return
	}
	if request.ID != nil && string(request.ID) == "null" {
		s.writeError(w, request.ID, -32600, "Request id cannot be null")
		return
	}

	if request.ID == nil {
		if request.Method == "notifications/initialized" {
			w.WriteHeader(http.StatusAccepted)
			return
		}
		w.WriteHeader(http.StatusAccepted)
		return
	}

	switch request.Method {
	case "initialize":
		s.writeResult(w, request.ID, map[string]any{
			"protocolVersion": protocolVersion,
			"capabilities":    map[string]any{"tools": map[string]any{}},
			"serverInfo":      map[string]string{"name": "connect-server", "version": "1.0.0"},
		})
	case "ping":
		s.writeResult(w, request.ID, map[string]any{})
	case "tools/list":
		s.writeResult(w, request.ID, map[string]any{"tools": tools()})
	case "tools/call":
		s.callTool(w, request)
	default:
		s.writeError(w, request.ID, -32601, "Method not found")
	}
}

func (s *Server) callTool(w http.ResponseWriter, request request) {
	var params struct {
		Name      string          `json:"name"`
		Arguments json.RawMessage `json:"arguments"`
	}
	if err := json.Unmarshal(request.Params, &params); err != nil || params.Name == "" {
		s.writeError(w, request.ID, -32602, "Invalid tool parameters")
		return
	}

	var output any
	switch params.Name {
	case "list_devices":
		collections := s.store.List()
		devices := make([]map[string]any, 0, len(collections))
		for _, collection := range collections {
			devices = append(devices, map[string]any{
				"deviceId":          collection.DeviceID,
				"deviceName":        collection.DeviceName,
				"collectedAt":       collection.CollectedAt,
				"receivedAt":        collection.ReceivedAt,
				"smsCount":          len(collection.SMSMessages),
				"notificationCount": len(collection.Notifications),
			})
		}
		output = devices
	case "get_latest_device_data":
		var arguments struct {
			DeviceID string `json:"device_id"`
		}
		if err := json.Unmarshal(params.Arguments, &arguments); err != nil || arguments.DeviceID == "" {
			s.writeError(w, request.ID, -32602, "device_id is required")
			return
		}
		collection, ok := s.store.Latest(arguments.DeviceID)
		if !ok {
			s.writeToolError(w, request.ID, "device not found")
			return
		}
		output = collection
	default:
		s.writeError(w, request.ID, -32602, "Unknown tool: "+params.Name)
		return
	}

	encoded, err := json.MarshalIndent(output, "", "  ")
	if err != nil {
		s.writeToolError(w, request.ID, "could not encode tool result")
		return
	}
	s.writeResult(w, request.ID, map[string]any{
		"content": []map[string]string{{"type": "text", "text": string(encoded)}},
		"isError": false,
	})
}

func tools() []map[string]any {
	return []map[string]any{
		{
			"name":        "list_devices",
			"description": "List devices with their latest collection timestamps and item counts.",
			"inputSchema": map[string]any{
				"type": "object", "properties": map[string]any{}, "additionalProperties": false,
			},
		},
		{
			"name":        "get_latest_device_data",
			"description": "Get the complete latest collected snapshot for one device.",
			"inputSchema": map[string]any{
				"type": "object",
				"properties": map[string]any{
					"device_id": map[string]string{"type": "string", "description": "Device ID returned by list_devices."},
				},
				"required":             []string{"device_id"},
				"additionalProperties": false,
			},
		},
	}
}

func (s *Server) writeToolError(w http.ResponseWriter, id json.RawMessage, message string) {
	s.writeResult(w, id, map[string]any{
		"content": []map[string]string{{"type": "text", "text": message}},
		"isError": true,
	})
}

func (s *Server) writeResult(w http.ResponseWriter, id json.RawMessage, result any) {
	s.writeJSON(w, response{JSONRPC: "2.0", ID: id, Result: result})
}

func (s *Server) writeError(w http.ResponseWriter, id json.RawMessage, code int, message string) {
	s.writeJSON(w, response{JSONRPC: "2.0", ID: id, Error: &rpcError{Code: code, Message: message}})
}

func (s *Server) writeJSON(w http.ResponseWriter, value response) {
	w.Header().Set("Content-Type", "application/json")
	if err := json.NewEncoder(w).Encode(value); err != nil {
		http.Error(w, fmt.Sprintf("encode response: %v", err), http.StatusInternalServerError)
	}
}

func validBearer(header, expected string) bool {
	const prefix = "Bearer "
	if !strings.HasPrefix(header, prefix) || expected == "" {
		return false
	}
	actualHash := sha256.Sum256([]byte(strings.TrimSpace(strings.TrimPrefix(header, prefix))))
	expectedHash := sha256.Sum256([]byte(expected))
	return subtle.ConstantTimeCompare(actualHash[:], expectedHash[:]) == 1
}
