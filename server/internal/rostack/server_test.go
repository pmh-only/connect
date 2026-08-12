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
	protocol, err := New(dataStore, Config{BaseURL: "https://connect.example.com", Token: "rostack-secret", APIVersion: "2026-08-13"})
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
		Protocol struct {
			Version string `json:"version"`
		} `json:"protocol"`
		Resources []struct {
			Name string `json:"name"`
		} `json:"resources"`
	}
	if err := json.NewDecoder(response.Body).Decode(&discovery); err != nil {
		t.Fatal(err)
	}
	if response.StatusCode != http.StatusOK || discovery.Protocol.Version != protocolVersion || len(discovery.Resources) != 1 || discovery.Resources[0].Name != resourceName {
		t.Fatalf("unexpected discovery document: %d %#v", response.StatusCode, discovery)
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

type devicePage struct {
	Items []model.Collection `json:"items"`
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
	if response.StatusCode != http.StatusOK || response.Header.Get("X-Rostack-API-Version") != "2026-08-13" {
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
