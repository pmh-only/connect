package mcp

import (
	"bytes"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"

	"connect/server/internal/model"
	"connect/server/internal/store"
)

func TestMCPRequiresBearerAndListsDevices(t *testing.T) {
	dataStore, err := store.Open(filepath.Join(t.TempDir(), "data.jsonl"))
	if err != nil {
		t.Fatal(err)
	}
	defer dataStore.Close()
	if _, err := dataStore.Add(model.Collection{DeviceID: "phone-1", DeviceName: "Phone"}); err != nil {
		t.Fatal(err)
	}
	handler := New(dataStore, "mcp-secret", nil).Handler()

	unauthorized := httptest.NewRequest(http.MethodPost, "/mcp", strings.NewReader(`{"jsonrpc":"2.0","id":1,"method":"initialize"}`))
	unauthorized.Header.Set("Content-Type", "application/json")
	unauthorizedResponse := httptest.NewRecorder()
	handler.ServeHTTP(unauthorizedResponse, unauthorized)
	if unauthorizedResponse.Code != http.StatusUnauthorized {
		t.Fatalf("unexpected unauthorized status: %d", unauthorizedResponse.Code)
	}

	requestBody := []byte(`{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"list_devices","arguments":{}}}`)
	request := httptest.NewRequest(http.MethodPost, "/mcp", bytes.NewReader(requestBody))
	request.Header.Set("Content-Type", "application/json")
	request.Header.Set("Authorization", "Bearer mcp-secret")
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, request)
	if response.Code != http.StatusOK {
		t.Fatalf("unexpected status: %d: %s", response.Code, response.Body.String())
	}
	if !strings.Contains(response.Body.String(), "phone-1") {
		t.Fatalf("tool result does not include device: %s", response.Body.String())
	}
}

func TestMCPPerCategoryDeviceDataTools(t *testing.T) {
	dataStore, err := store.Open(filepath.Join(t.TempDir(), "data.jsonl"))
	if err != nil {
		t.Fatal(err)
	}
	defer dataStore.Close()
	steps := int64(1234)
	if _, err := dataStore.Add(model.Collection{
		DeviceID:      "phone-1",
		DeviceName:    "Phone",
		Health:        &model.HealthSnapshot{Steps: &steps},
		SMSMessages:   []model.SMSSnapshot{{ID: 1, Address: "123", Body: "hi", Timestamp: 1}},
		Notifications: []model.NotificationSnapshot{{Key: "k", PackageName: "pkg", Title: "t", Text: "body", Timestamp: 1}},
		Battery:       &model.BatterySnapshot{LevelPercent: 80},
	}); err != nil {
		t.Fatal(err)
	}
	handler := New(dataStore, "mcp-secret", nil).Handler()

	callTool := func(name, deviceID string) *httptest.ResponseRecorder {
		body := []byte(`{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"` + name + `","arguments":{"device_id":"` + deviceID + `"}}}`)
		request := httptest.NewRequest(http.MethodPost, "/mcp", bytes.NewReader(body))
		request.Header.Set("Content-Type", "application/json")
		request.Header.Set("Authorization", "Bearer mcp-secret")
		response := httptest.NewRecorder()
		handler.ServeHTTP(response, request)
		return response
	}

	healthResponse := callTool("get_health_data", "phone-1")
	if healthResponse.Code != http.StatusOK || !strings.Contains(healthResponse.Body.String(), "1234") {
		t.Fatalf("unexpected get_health_data response: %d: %s", healthResponse.Code, healthResponse.Body.String())
	}

	smsResponse := callTool("get_sms_messages", "phone-1")
	if smsResponse.Code != http.StatusOK || !strings.Contains(smsResponse.Body.String(), "hi") {
		t.Fatalf("unexpected get_sms_messages response: %d: %s", smsResponse.Code, smsResponse.Body.String())
	}
	if strings.Contains(smsResponse.Body.String(), "notifications") {
		t.Fatalf("get_sms_messages leaked unrelated data: %s", smsResponse.Body.String())
	}

	noDataResponse := callTool("get_location", "phone-1")
	if noDataResponse.Code != http.StatusOK || !strings.Contains(noDataResponse.Body.String(), "\"isError\":true") {
		t.Fatalf("expected isError for missing location data: %d: %s", noDataResponse.Code, noDataResponse.Body.String())
	}

	unknownDeviceResponse := callTool("get_battery_status", "missing")
	if unknownDeviceResponse.Code != http.StatusOK || !strings.Contains(unknownDeviceResponse.Body.String(), "device not found") {
		t.Fatalf("expected device not found error: %d: %s", unknownDeviceResponse.Code, unknownDeviceResponse.Body.String())
	}
}
