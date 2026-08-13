package rostack

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"connect/server/internal/model"
	"connect/server/internal/store"
	"github.com/gorilla/websocket"
)

func newTestServer(t *testing.T) (*store.Store, *httptest.Server) {
	t.Helper()
	dataStore, err := store.Open(filepath.Join(t.TempDir(), "data.jsonl"))
	if err != nil {
		t.Fatal(err)
	}
	protocol, err := New(dataStore, Config{BaseURL: "https://connect.example.com", Token: "rostack-secret", APIVersion: "2026-08-13.1"})
	if err != nil {
		dataStore.Close()
		t.Fatal(err)
	}
	mux := http.NewServeMux()
	protocol.Register(mux)
	httpServer := httptest.NewServer(mux)
	t.Cleanup(func() {
		httpServer.Close()
		dataStore.Close()
	})
	return dataStore, httpServer
}

func TestDiscoveryAdvertisesDeviceResource(t *testing.T) {
	_, httpServer := newTestServer(t)
	response, err := http.Get(httpServer.URL + "/.well-known/rostack")
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	var discovery struct {
		Implementation struct {
			DocumentationURL string `json:"documentation_url"`
		} `json:"implementation"`
		Protocol struct {
			Version string `json:"version"`
		} `json:"protocol"`
		Resources []struct {
			Name            string `json:"name"`
			ItemURLTemplate string `json:"item_url_template"`
		} `json:"resources"`
		Errors struct {
			HTTP      []any `json:"http"`
			WebSocket []any `json:"websocket"`
		} `json:"errors"`
	}
	if err := json.NewDecoder(response.Body).Decode(&discovery); err != nil {
		t.Fatal(err)
	}
	if response.StatusCode != http.StatusOK || discovery.Protocol.Version != protocolVersion || len(discovery.Resources) != 1 || discovery.Resources[0].Name != resourceName ||
		discovery.Implementation.DocumentationURL == "" || !strings.HasSuffix(discovery.Resources[0].ItemURLTemplate, "/devices/{id}") ||
		len(discovery.Errors.HTTP) == 0 || len(discovery.Errors.WebSocket) == 0 {
		t.Fatalf("unexpected discovery document: %d %#v", response.StatusCode, discovery)
	}
	if response.Header.Get("ETag") == "" || response.Header.Get("Cache-Control") == "" {
		t.Fatal("discovery is missing cache headers")
	}
}

func TestDeviceCollectionRequiresTokenAndKeepsPaginationSnapshot(t *testing.T) {
	dataStore, httpServer := newTestServer(t)
	for _, id := range []string{"b", "c"} {
		if _, err := dataStore.Add(model.Collection{DeviceID: id, DeviceName: id}); err != nil {
			t.Fatal(err)
		}
	}
	unauthorized, err := http.Get(httpServer.URL + "/rostack/v1/devices")
	if err != nil {
		t.Fatal(err)
	}
	unauthorized.Body.Close()
	if unauthorized.StatusCode != http.StatusUnauthorized || unauthorized.Header.Get("WWW-Authenticate") != "Rostack-Token" {
		t.Fatalf("unexpected unauthorized response: %d", unauthorized.StatusCode)
	}
	authorized, err := http.NewRequest(http.MethodGet, httpServer.URL+"/rostack/v1/devices", nil)
	if err != nil {
		t.Fatal(err)
	}
	authorized.Header.Set("Authorization", "rostack-token rostack-secret")
	response, err := http.DefaultClient.Do(authorized)
	if err != nil {
		t.Fatal(err)
	}
	response.Body.Close()
	if response.StatusCode != http.StatusOK {
		t.Fatalf("case-insensitive authentication scheme failed: %d", response.StatusCode)
	}
	first := getDevices(t, httpServer.URL+"/rostack/v1/devices?limit=1")
	if len(first.Items) != 1 || first.Items[0].DeviceID != "b" || first.Page.NextCursor == nil || first.Page.EventCursor == "" {
		t.Fatalf("unexpected first page: %#v", first)
	}
	if _, err := dataStore.Add(model.Collection{DeviceID: "a", DeviceName: "a"}); err != nil {
		t.Fatal(err)
	}
	second := getDevices(t, httpServer.URL+"/rostack/v1/devices?limit=1&cursor="+*first.Page.NextCursor)
	if len(second.Items) != 1 || second.Items[0].DeviceID != "c" || second.Page.EventCursor != first.Page.EventCursor {
		t.Fatalf("continuation did not preserve snapshot: %#v", second)
	}
}

func TestHTTPProblemsUseRegistryAndResourcesMatchClosedSchema(t *testing.T) {
	dataStore, httpServer := newTestServer(t)
	if _, err := dataStore.Add(model.Collection{DeviceID: "phone-1", DeviceName: "Phone", Health: &model.HealthSnapshot{}}); err != nil {
		t.Fatal(err)
	}
	page := getDevices(t, httpServer.URL+"/rostack/v1/devices")
	encoded, err := json.Marshal(page.Items[0])
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(encoded), "health") || !strings.Contains(string(encoded), `"truncatedForUpload":false`) {
		t.Fatalf("resource does not match closed summary schema: %s", encoded)
	}

	tests := []struct {
		method, path, problem string
		status                int
	}{
		{http.MethodGet, "/rostack/v1/devices?filter=%7B%7D", "unsupported-filter", 400},
		{http.MethodGet, "/rostack/v1/devices?sort=/deviceId", "invalid-sort", 400},
		{http.MethodGet, "/rostack/v1/devices?fields=/deviceId", "invalid-fields", 400},
		{http.MethodGet, "/rostack/v1/devices?cursor=bad", "invalid-cursor", 400},
		{http.MethodGet, "/rostack/v1/devices/missing", "resource-not-found", 404},
		{http.MethodPost, "/rostack/v1/devices", "method-not-allowed", 405},
	}
	for _, test := range tests {
		request, err := http.NewRequest(test.method, httpServer.URL+test.path, nil)
		if err != nil {
			t.Fatal(err)
		}
		request.Header.Set("Authorization", "Rostack-Token rostack-secret")
		response, err := http.DefaultClient.Do(request)
		if err != nil {
			t.Fatal(err)
		}
		var problem map[string]any
		if err := json.NewDecoder(response.Body).Decode(&problem); err != nil {
			response.Body.Close()
			t.Fatal(err)
		}
		response.Body.Close()
		if response.StatusCode != test.status || problem["type"] != problemPrefix+test.problem || problem["status"] != float64(test.status) {
			t.Fatalf("unexpected problem for %s: %d %#v", test.path, response.StatusCode, problem)
		}
	}
}

func TestDeviceSchemaIsCanonicalAndClosed(t *testing.T) {
	_, httpServer := newTestServer(t)
	response, err := http.Get(httpServer.URL + "/rostack/v1/schemas/device.json")
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	var schema map[string]any
	if err := json.NewDecoder(response.Body).Decode(&schema); err != nil {
		t.Fatal(err)
	}
	if schema["$id"] != "https://connect.example.com/rostack/v1/schemas/device.json" || schema["$schema"] != "https://json-schema.org/draft/2020-12/schema" || schema["additionalProperties"] != false {
		t.Fatalf("implementation schema is not canonical and closed: %#v", schema)
	}
}

type devicePage struct {
	Items []device `json:"items"`
	Page  struct {
		NextCursor  *string `json:"next_cursor"`
		EventCursor string  `json:"event_cursor"`
	} `json:"page"`
}

func getDevices(t *testing.T, endpoint string) devicePage {
	t.Helper()
	request, err := http.NewRequest(http.MethodGet, endpoint, nil)
	if err != nil {
		t.Fatal(err)
	}
	request.Header.Set("Authorization", "Rostack-Token rostack-secret")
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	var page devicePage
	if err := json.NewDecoder(response.Body).Decode(&page); err != nil {
		t.Fatal(err)
	}
	if response.StatusCode != http.StatusOK || response.Header.Get("X-Rostack-API-Version") != "2026-08-13.1" {
		t.Fatalf("unexpected collection response: %d", response.StatusCode)
	}
	return page
}

func TestWebSocketAuthenticatesSubscribesAndEmitsUpdates(t *testing.T) {
	dataStore, httpServer := newTestServer(t)
	websocketURL := "ws" + strings.TrimPrefix(httpServer.URL, "http") + "/rostack/v1/events"
	dialer := websocket.Dialer{Subprotocols: []string{"rostack.v1"}}
	connection, response, err := dialer.Dial(websocketURL, nil)
	if err != nil {
		if response != nil {
			t.Fatalf("dial failed with %d: %v", response.StatusCode, err)
		}
		t.Fatal(err)
	}
	defer connection.Close()
	connection.SetReadDeadline(time.Now().Add(5 * time.Second))
	writeWS(t, connection, map[string]any{"type": "authenticate", "method": "shared_token", "token": "rostack-secret"})
	if message := readWS(t, connection); message["type"] != "authenticated" || message["principal_id"] == "" {
		t.Fatalf("unexpected authentication: %#v", message)
	}
	writeWS(t, connection, map[string]any{"type": "subscribe", "subscription_id": "devices-1", "resource": "devices"})
	if message := readWS(t, connection); message["type"] != "subscribed" || message["replaying"] != false {
		t.Fatalf("unexpected subscription: %#v", message)
	}
	writeWS(t, connection, map[string]any{"type": "subscribe", "subscription_id": "devices-1", "resource": "devices"})
	if message := readWS(t, connection); message["type"] != "subscribed" {
		t.Fatalf("idempotent subscription failed: %#v", message)
	}
	if _, err := dataStore.Add(model.Collection{DeviceID: "phone-1", DeviceName: "Phone"}); err != nil {
		t.Fatal(err)
	}
	message := readWS(t, connection)
	if message["type"] != "event" || message["event_type"] != "device.created" || message["resource_id"] != "phone-1" || message["cursor"] == "" {
		t.Fatalf("unexpected event: %#v", message)
	}
}

func TestWebSocketUsesRegisteredSubscriptionErrors(t *testing.T) {
	_, httpServer := newTestServer(t)
	websocketURL := "ws" + strings.TrimPrefix(httpServer.URL, "http") + "/rostack/v1/events"
	dialer := websocket.Dialer{Subprotocols: []string{"rostack.v1"}}
	connection, _, err := dialer.Dial(websocketURL, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer connection.Close()
	connection.SetReadDeadline(time.Now().Add(5 * time.Second))
	writeWS(t, connection, map[string]any{"type": "authenticate", "method": "shared_token", "token": "rostack-secret"})
	readWS(t, connection)

	tests := []struct {
		message map[string]any
		code    string
	}{
		{map[string]any{"type": "subscribe", "subscription_id": "a", "resource": "missing"}, "resource_not_found"},
		{map[string]any{"type": "subscribe", "subscription_id": "b", "resource": "devices", "event_types": []string{"unknown"}}, "unsupported_event_type"},
		{map[string]any{"type": "subscribe", "subscription_id": "c", "resource": "devices", "filter": map[string]any{"/deviceId": map[string]any{"eq": "x"}}}, "unsupported_filter"},
		{map[string]any{"type": "subscribe", "subscription_id": "d", "resource": "devices", "event_encoding": "compact-json"}, "unsupported_encoding"},
	}
	for _, test := range tests {
		writeWS(t, connection, test.message)
		message := readWS(t, connection)
		if message["type"] != "error" || message["code"] != test.code || message["retryable"] != false || message["subscription_id"] == "" {
			t.Fatalf("unexpected WebSocket error: %#v", message)
		}
	}
}

func TestWebSocketReplaysStrictlyAfterSnapshotCursor(t *testing.T) {
	dataStore, httpServer := newTestServer(t)
	if _, err := dataStore.Add(model.Collection{DeviceID: "phone-1", DeviceName: "Before"}); err != nil {
		t.Fatal(err)
	}
	snapshot := getDevices(t, httpServer.URL+"/rostack/v1/devices")
	if _, err := dataStore.Add(model.Collection{DeviceID: "phone-1", DeviceName: "After"}); err != nil {
		t.Fatal(err)
	}

	websocketURL := "ws" + strings.TrimPrefix(httpServer.URL, "http") + "/rostack/v1/events"
	dialer := websocket.Dialer{Subprotocols: []string{"rostack.v1"}}
	connection, _, err := dialer.Dial(websocketURL, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer connection.Close()
	connection.SetReadDeadline(time.Now().Add(5 * time.Second))
	writeWS(t, connection, map[string]any{"type": "authenticate", "method": "shared_token", "token": "rostack-secret"})
	readWS(t, connection)
	writeWS(t, connection, map[string]any{
		"type": "subscribe", "subscription_id": "replay-1", "resource": "devices", "cursor": snapshot.Page.EventCursor,
	})
	if message := readWS(t, connection); message["type"] != "subscribed" || message["replaying"] != true {
		t.Fatalf("unexpected replay state: %#v", message)
	}
	if message := readWS(t, connection); message["type"] != "event" || message["event_type"] != "device.updated" {
		t.Fatalf("unexpected replay event: %#v", message)
	}
	if message := readWS(t, connection); message["type"] != "replay_complete" {
		t.Fatalf("missing replay completion: %#v", message)
	}
}

func writeWS(t *testing.T, connection *websocket.Conn, value any) {
	t.Helper()
	if err := connection.WriteJSON(value); err != nil {
		t.Fatal(err)
	}
}

func readWS(t *testing.T, connection *websocket.Conn) map[string]any {
	t.Helper()
	var value map[string]any
	if err := connection.ReadJSON(&value); err != nil {
		t.Fatal(err)
	}
	return value
}
