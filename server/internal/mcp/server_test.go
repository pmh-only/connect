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
