package web

import (
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"

	"connect/server/internal/store"
)

func TestCollectRequiresTokenAndStoresPayload(t *testing.T) {
	dataStore, err := store.Open(filepath.Join(t.TempDir(), "data.jsonl"))
	if err != nil {
		t.Fatal(err)
	}
	defer dataStore.Close()
	server, err := New(dataStore, "collect-secret", nil)
	if err != nil {
		t.Fatal(err)
	}

	unauthorized := httptest.NewRequest(http.MethodPost, "/api/collect", strings.NewReader(`{"deviceId":"phone-1"}`))
	unauthorized.Header.Set("Content-Type", "application/json")
	unauthorizedResponse := httptest.NewRecorder()
	server.collect(unauthorizedResponse, unauthorized)
	if unauthorizedResponse.Code != http.StatusUnauthorized {
		t.Fatalf("unexpected unauthorized status: %d", unauthorizedResponse.Code)
	}

	request := httptest.NewRequest(http.MethodPost, "/api/collect", strings.NewReader(`{"deviceId":"phone-1","deviceName":"Phone"}`))
	request.Header.Set("Content-Type", "application/json")
	request.Header.Set("Authorization", "Bearer collect-secret")
	response := httptest.NewRecorder()
	server.collect(response, request)
	if response.Code != http.StatusAccepted {
		t.Fatalf("unexpected status: %d: %s", response.Code, response.Body.String())
	}
	if collection, ok := dataStore.Latest("phone-1"); !ok || collection.DeviceName != "Phone" {
		t.Fatalf("collection not stored: %#v, %v", collection, ok)
	}
}
