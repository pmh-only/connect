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
	protocol, err := New(dataStore, Config{BaseURL: "https://connect.example.com", Token: "rostack-secret", APIVersion: "2026-08-13.2"})
	if err != nil {
		t.Fatal(err)
	}
	mux := http.NewServeMux()
	protocol.Register(mux)
	httpServer := httptest.NewServer(mux)
	t.Cleanup(func() { httpServer.Close(); dataStore.Close() })
	return dataStore, httpServer
}

func TestDiscoveryAdvertisesIndividualTelemetryResources(t *testing.T) {
	_, httpServer := newTestServer(t)
	response, err := http.Get(httpServer.URL + "/.well-known/rostack")
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	var discovery struct {
		Resources []struct {
			Name   string `json:"name"`
			Events []struct {
				Name string `json:"name"`
			} `json:"events"`
		} `json:"resources"`
	}
	if err := json.NewDecoder(response.Body).Decode(&discovery); err != nil {
		t.Fatal(err)
	}
	names := make(map[string]bool)
	for _, resource := range discovery.Resources {
		names[resource.Name] = true
		if len(resource.Events) != 3 || resource.Events[0].Name != resource.Name+".created" {
			t.Fatalf("resource has incomplete events: %#v", resource)
		}
	}
	for _, required := range []string{
		"health-steps", "health-records", "sms-messages", "notifications",
		"battery-level-percent", "location-latitude", "location-history",
		"location-address-locality", "location-status-providers", "gnss-satellites",
		"truncated-for-upload",
	} {
		if !names[required] {
			t.Fatalf("missing resource %q", required)
		}
	}
	if names["devices"] {
		t.Fatal("super device resource must not be advertised")
	}
}

func TestResourceCatalogCoversEveryCollectionField(t *testing.T) {
	covered := make(map[int]bool)
	for _, definition := range resourceCatalog {
		covered[definition.fieldPath[0]] = true
	}
	for index := 4; index < collectionType.NumField(); index++ {
		if !covered[index] {
			t.Fatalf("collection field %s has no rostack resource", collectionType.Field(index).Name)
		}
	}
}

func TestMetricResourcesIncludeValuesFromEveryDevice(t *testing.T) {
	dataStore, httpServer := newTestServer(t)
	stepsA, stepsB := int64(10), int64(20)
	for _, collection := range []model.Collection{
		{DeviceID: "a", DeviceName: "Alpha", Health: &model.HealthSnapshot{Steps: &stepsA}},
		{DeviceID: "b", DeviceName: "Beta", Health: &model.HealthSnapshot{Steps: &stepsB}},
		{DeviceID: "c", DeviceName: "No health"},
	} {
		if _, err := dataStore.Add(collection); err != nil {
			t.Fatal(err)
		}
	}
	page := getResourcePage(t, httpServer.URL+"/rostack/v1/health-steps")
	if len(page.Items) != 2 || page.Items[0].DeviceID != "a" || page.Items[0].Value != float64(10) || page.Items[1].Value != float64(20) {
		t.Fatalf("unexpected metric page: %#v", page)
	}
	item := getResourceItem(t, httpServer.URL+"/rostack/v1/health-steps/b")
	if item.DeviceID != "b" || item.Value != float64(20) {
		t.Fatalf("unexpected metric item: %#v", item)
	}
	request, _ := http.NewRequest(http.MethodGet, httpServer.URL+"/rostack/v1/devices", nil)
	request.Header.Set("Authorization", "Rostack-Token rostack-secret")
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	response.Body.Close()
	if response.StatusCode != http.StatusNotFound {
		t.Fatalf("super resource still exists: %d", response.StatusCode)
	}
}

func TestCollectionEventResourcesExposeFullPayload(t *testing.T) {
	dataStore, httpServer := newTestServer(t)
	if _, err := dataStore.Add(model.Collection{DeviceID: "phone", SMSMessages: []model.SMSSnapshot{{ID: 7, Address: "+1", Body: "hello", Timestamp: 10, Type: 1}}}); err != nil {
		t.Fatal(err)
	}
	item := getResourceItem(t, httpServer.URL+"/rostack/v1/sms-messages/phone")
	messages, ok := item.Value.([]any)
	if !ok || len(messages) != 1 || messages[0].(map[string]any)["body"] != "hello" {
		t.Fatalf("unexpected SMS resource: %#v", item)
	}
}

func TestResourceSchemaIsCanonicalAndClosed(t *testing.T) {
	_, httpServer := newTestServer(t)
	response, err := http.Get(httpServer.URL + "/rostack/v1/schemas/health-records.json")
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	var schema map[string]any
	if err := json.NewDecoder(response.Body).Decode(&schema); err != nil {
		t.Fatal(err)
	}
	if schema["$id"] != "https://connect.example.com/rostack/v1/schemas/health-records.json" || schema["additionalProperties"] != false {
		t.Fatalf("unexpected schema: %#v", schema)
	}
}

func TestWebSocketEmitsPerMetricCreateUpdateDelete(t *testing.T) {
	dataStore, httpServer := newTestServer(t)
	connection := connectWS(t, httpServer)
	defer connection.Close()
	writeWS(t, connection, map[string]any{"type": "subscribe", "subscription_id": "steps", "resource": "health-steps"})
	if message := readWS(t, connection); message["type"] != "subscribed" {
		t.Fatalf("unexpected subscription: %#v", message)
	}
	steps := int64(10)
	if _, err := dataStore.Add(model.Collection{DeviceID: "phone", Health: &model.HealthSnapshot{Steps: &steps}}); err != nil {
		t.Fatal(err)
	}
	assertEventType(t, readWS(t, connection), "health-steps.created")
	steps = 20
	if _, err := dataStore.Add(model.Collection{DeviceID: "phone", Health: &model.HealthSnapshot{Steps: &steps}}); err != nil {
		t.Fatal(err)
	}
	assertEventType(t, readWS(t, connection), "health-steps.updated")
	if _, err := dataStore.Add(model.Collection{DeviceID: "phone"}); err != nil {
		t.Fatal(err)
	}
	assertEventType(t, readWS(t, connection), "health-steps.deleted")
}

func TestWebSocketReplayCursorIsResourceScoped(t *testing.T) {
	dataStore, httpServer := newTestServer(t)
	steps := int64(10)
	if _, err := dataStore.Add(model.Collection{DeviceID: "phone", Health: &model.HealthSnapshot{Steps: &steps}}); err != nil {
		t.Fatal(err)
	}
	snapshot := getResourcePage(t, httpServer.URL+"/rostack/v1/health-steps")
	steps = 20
	if _, err := dataStore.Add(model.Collection{DeviceID: "phone", Health: &model.HealthSnapshot{Steps: &steps}}); err != nil {
		t.Fatal(err)
	}
	connection := connectWS(t, httpServer)
	defer connection.Close()
	writeWS(t, connection, map[string]any{"type": "subscribe", "subscription_id": "steps", "resource": "health-steps", "cursor": snapshot.Page.EventCursor})
	if message := readWS(t, connection); message["replaying"] != true {
		t.Fatalf("unexpected replay state: %#v", message)
	}
	assertEventType(t, readWS(t, connection), "health-steps.updated")
	if message := readWS(t, connection); message["type"] != "replay_complete" {
		t.Fatalf("missing replay completion: %#v", message)
	}
}

type resourcePage struct {
	Items []resourceItem `json:"items"`
	Page  struct {
		EventCursor string `json:"event_cursor"`
	} `json:"page"`
}

func getResourcePage(t *testing.T, endpoint string) resourcePage {
	t.Helper()
	request, _ := http.NewRequest(http.MethodGet, endpoint, nil)
	request.Header.Set("Authorization", "Rostack-Token rostack-secret")
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	var page resourcePage
	if err := json.NewDecoder(response.Body).Decode(&page); err != nil {
		t.Fatal(err)
	}
	if response.StatusCode != http.StatusOK {
		t.Fatalf("unexpected status: %d", response.StatusCode)
	}
	return page
}

func getResourceItem(t *testing.T, endpoint string) resourceItem {
	t.Helper()
	request, _ := http.NewRequest(http.MethodGet, endpoint, nil)
	request.Header.Set("Authorization", "Rostack-Token rostack-secret")
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	var item resourceItem
	if err := json.NewDecoder(response.Body).Decode(&item); err != nil {
		t.Fatal(err)
	}
	return item
}

func connectWS(t *testing.T, httpServer *httptest.Server) *websocket.Conn {
	t.Helper()
	websocketURL := "ws" + strings.TrimPrefix(httpServer.URL, "http") + "/rostack/v1/events"
	connection, _, err := (&websocket.Dialer{Subprotocols: []string{"rostack.v1"}}).Dial(websocketURL, nil)
	if err != nil {
		t.Fatal(err)
	}
	connection.SetReadDeadline(time.Now().Add(5 * time.Second))
	writeWS(t, connection, map[string]any{"type": "authenticate", "method": "shared_token", "token": "rostack-secret"})
	if message := readWS(t, connection); message["type"] != "authenticated" {
		t.Fatalf("unexpected authentication: %#v", message)
	}
	return connection
}

func assertEventType(t *testing.T, message map[string]any, eventType string) {
	t.Helper()
	if message["type"] != "event" || message["event_type"] != eventType || message["data"] == nil || message["cursor"] == "" {
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
