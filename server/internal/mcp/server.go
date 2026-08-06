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

	"connect/server/internal/model"
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

// deviceDataTools maps a tool name to a function that extracts the relevant
// slice of the device's latest collection. The second return value reports
// whether any data was present.
var deviceDataTools = map[string]func(model.Collection) (any, bool){
	"get_health_data": func(c model.Collection) (any, bool) {
		if c.Health == nil {
			return nil, false
		}
		return c.Health, true
	},
	"get_sms_messages": func(c model.Collection) (any, bool) {
		if len(c.SMSMessages) == 0 {
			return nil, false
		}
		return c.SMSMessages, true
	},
	"get_notifications": func(c model.Collection) (any, bool) {
		if len(c.Notifications) == 0 {
			return nil, false
		}
		return c.Notifications, true
	},
	"get_battery_status": func(c model.Collection) (any, bool) {
		if c.Battery == nil {
			return nil, false
		}
		return c.Battery, true
	},
	"get_location": func(c model.Collection) (any, bool) {
		if c.Location == nil {
			return nil, false
		}
		return c.Location, true
	},
	"get_location_history": func(c model.Collection) (any, bool) {
		if len(c.LocationHistory) == 0 {
			return nil, false
		}
		return c.LocationHistory, true
	},
	"get_location_status": func(c model.Collection) (any, bool) {
		if c.LocationStatus == nil {
			return nil, false
		}
		return c.LocationStatus, true
	},
	"get_gnss_data": func(c model.Collection) (any, bool) {
		if c.GNSS == nil {
			return nil, false
		}
		return c.GNSS, true
	},
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

	if params.Name == "list_devices" {
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
		s.writeToolResult(w, request.ID, devices)
		return
	}

	extract, ok := deviceDataTools[params.Name]
	if !ok {
		s.writeError(w, request.ID, -32602, "Unknown tool: "+params.Name)
		return
	}

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

	output, present := extract(collection)
	if !present {
		s.writeToolError(w, request.ID, "no data available")
		return
	}
	s.writeToolResult(w, request.ID, output)
}

func (s *Server) writeToolResult(w http.ResponseWriter, id json.RawMessage, output any) {
	encoded, err := json.MarshalIndent(output, "", "  ")
	if err != nil {
		s.writeToolError(w, id, "could not encode tool result")
		return
	}
	s.writeResult(w, id, map[string]any{
		"content": []map[string]string{{"type": "text", "text": string(encoded)}},
		"isError": false,
	})
}

func deviceIDInputSchema() map[string]any {
	return map[string]any{
		"type": "object",
		"properties": map[string]any{
			"device_id": map[string]string{"type": "string", "description": "Device ID returned by list_devices."},
		},
		"required":             []string{"device_id"},
		"additionalProperties": false,
	}
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
			"name":        "get_health_data",
			"description": "Get the latest Health Connect snapshot for one device (steps, heart rate, sleep, records, etc.).",
			"inputSchema": deviceIDInputSchema(),
		},
		{
			"name":        "get_sms_messages",
			"description": "Get the latest collected SMS messages for one device.",
			"inputSchema": deviceIDInputSchema(),
		},
		{
			"name":        "get_notifications",
			"description": "Get the latest collected notifications for one device.",
			"inputSchema": deviceIDInputSchema(),
		},
		{
			"name":        "get_battery_status",
			"description": "Get the latest battery status for one device.",
			"inputSchema": deviceIDInputSchema(),
		},
		{
			"name":        "get_location",
			"description": "Get the latest single location fix for one device.",
			"inputSchema": deviceIDInputSchema(),
		},
		{
			"name":        "get_location_history",
			"description": "Get the latest collected location history for one device.",
			"inputSchema": deviceIDInputSchema(),
		},
		{
			"name":        "get_location_status",
			"description": "Get the latest location provider status (enabled providers, GNSS hardware) for one device.",
			"inputSchema": deviceIDInputSchema(),
		},
		{
			"name":        "get_gnss_data",
			"description": "Get the latest raw GNSS satellite data for one device.",
			"inputSchema": deviceIDInputSchema(),
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
