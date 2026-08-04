package store

import (
	"path/filepath"
	"testing"

	"connect/server/internal/model"
)

func TestStorePersistsLatestCollection(t *testing.T) {
	path := filepath.Join(t.TempDir(), "collections.jsonl")
	dataStore, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	stored, err := dataStore.Add(model.Collection{DeviceID: "device-1", DeviceName: "Phone"})
	if err != nil {
		t.Fatal(err)
	}
	if stored.ReceivedAt == 0 {
		t.Fatal("expected server receive timestamp")
	}
	if err := dataStore.Close(); err != nil {
		t.Fatal(err)
	}

	reopened, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer reopened.Close()
	collection, ok := reopened.Latest("device-1")
	if !ok || collection.DeviceName != "Phone" {
		t.Fatalf("unexpected collection: %#v, %v", collection, ok)
	}
}
