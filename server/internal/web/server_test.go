package web

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"

	"connect/server/internal/model"
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

	request := httptest.NewRequest(http.MethodPost, "/api/collect", strings.NewReader(`{"deviceId":"phone-1","deviceName":"Phone","health":{"steps":8421,"sleepMinutes":438,"averageHeartRateBpm":72,"weightKilograms":74.5,"supportedRecordTypes":["BloodPressureRecord"],"grantedRecordTypes":["BloodPressureRecord"],"records":[{"id":"record-1","recordType":"BloodPressureRecord","startTime":1000,"lastModifiedTime":1001,"dataOrigin":"health.app","recordingMethod":2,"clientRecordVersion":0,"data":{"systolicMillimetersOfMercury":120,"diastolicMillimetersOfMercury":80}}]},"location":{"latitude":37.5,"longitude":127.0,"accuracyMeters":4.5,"bearingDegrees":92.0,"provider":"gps","timestamp":2000,"elapsedRealtimeNanos":3000,"ageAtReceiptMillis":12,"isMock":false,"extras":{"satellites":"9"},"address":{"sourceLocationElapsedRealtimeNanos":3000,"sourceProvider":"gps","locality":"Seoul","addressLines":["Seoul"],"resolvedAt":2100}},"locationHistory":[{"latitude":37.5,"longitude":127.0,"provider":"gps","timestamp":2000,"elapsedRealtimeNanos":3000,"ageAtReceiptMillis":12,"isMock":false}],"locationStatus":{"locationEnabled":true,"providers":[{"name":"gps","enabled":true,"propertiesKnown":true}],"timestamp":2000},"gnss":{"running":true,"reportedSatelliteCount":1,"satellitesTruncated":false,"satellites":[{"constellationType":1,"svid":7,"cn0DbHz":35.5,"elevationDegrees":45,"azimuthDegrees":120,"hasEphemerisData":true,"hasAlmanacData":true,"usedInFix":true}],"capturedAt":2000,"capturedAtElapsedRealtimeNanos":3000}}`))
	request.Header.Set("Content-Type", "application/json")
	request.Header.Set("Authorization", "Bearer collect-secret")
	response := httptest.NewRecorder()
	server.collect(response, request)
	if response.Code != http.StatusAccepted {
		t.Fatalf("unexpected status: %d: %s", response.Code, response.Body.String())
	}
	collection, ok := dataStore.Latest("phone-1")
	if !ok || collection.DeviceName != "Phone" {
		t.Fatalf("collection not stored: %#v, %v", collection, ok)
	}
	if collection.Health == nil || collection.Health.SleepMinutes == nil ||
		*collection.Health.SleepMinutes != 438 || collection.Health.WeightKilograms == nil ||
		*collection.Health.WeightKilograms != 74.5 {
		t.Fatalf("health snapshot not stored: %#v", collection.Health)
	}
	if len(collection.Health.Records) != 1 ||
		collection.Health.Records[0].RecordType != "BloodPressureRecord" ||
		!strings.Contains(string(collection.Health.Records[0].Data), `"systolicMillimetersOfMercury":120`) {
		t.Fatalf("detailed health records not stored: %#v", collection.Health.Records)
	}
	if collection.Location == nil || collection.Location.BearingDegrees == nil ||
		*collection.Location.BearingDegrees != 92 || len(collection.LocationHistory) != 1 ||
		collection.GNSS == nil || len(collection.GNSS.Satellites) != 1 ||
		collection.LocationStatus == nil || len(collection.LocationStatus.Providers) != 1 {
		t.Fatalf("detailed location data not stored: %#v", collection)
	}
}

func TestCollectRejectsMoreThanOneHundredMessagesWithReason(t *testing.T) {
	dataStore, err := store.Open(filepath.Join(t.TempDir(), "data.jsonl"))
	if err != nil {
		t.Fatal(err)
	}
	defer dataStore.Close()
	server, err := New(dataStore, "collect-secret", nil)
	if err != nil {
		t.Fatal(err)
	}
	messages := make([]model.SMSSnapshot, 101)
	payload, err := json.Marshal(model.Collection{DeviceID: "phone-1", SMSMessages: messages})
	if err != nil {
		t.Fatal(err)
	}
	request := httptest.NewRequest(http.MethodPost, "/api/collect", bytes.NewReader(payload))
	request.Header.Set("Content-Type", "application/json")
	request.Header.Set("Authorization", "Bearer collect-secret")
	response := httptest.NewRecorder()
	server.collect(response, request)
	if response.Code != http.StatusBadRequest {
		t.Fatalf("unexpected status: %d: %s", response.Code, response.Body.String())
	}
	if !strings.Contains(response.Body.String(), "limited to 100") {
		t.Fatalf("missing rejection reason: %s", response.Body.String())
	}
}

func TestCollectRejectsTooManyDetailedHealthRecords(t *testing.T) {
	dataStore, err := store.Open(filepath.Join(t.TempDir(), "data.jsonl"))
	if err != nil {
		t.Fatal(err)
	}
	defer dataStore.Close()
	server, err := New(dataStore, "collect-secret", nil)
	if err != nil {
		t.Fatal(err)
	}
	records := make([]model.HealthRecordSnapshot, 124)
	payload, err := json.Marshal(model.Collection{
		DeviceID: "phone-1",
		Health:   &model.HealthSnapshot{Records: records},
	})
	if err != nil {
		t.Fatal(err)
	}
	request := httptest.NewRequest(http.MethodPost, "/api/collect", bytes.NewReader(payload))
	request.Header.Set("Content-Type", "application/json")
	request.Header.Set("Authorization", "Bearer collect-secret")
	response := httptest.NewRecorder()
	server.collect(response, request)
	if response.Code != http.StatusBadRequest {
		t.Fatalf("unexpected status: %d: %s", response.Code, response.Body.String())
	}
	if !strings.Contains(response.Body.String(), "limited to 123") {
		t.Fatalf("missing rejection reason: %s", response.Body.String())
	}
}

func TestDashboardRendersNullableHealthSummaryAndCompleteness(t *testing.T) {
	server, err := New(nil, "", nil)
	if err != nil {
		t.Fatal(err)
	}
	steps := int64(0)
	exerciseSessions := 1
	exerciseMinutes := int64(30)
	var response bytes.Buffer
	err = server.template.ExecuteTemplate(&response, "dashboard.html", dashboardData{
		Devices: []model.Collection{{
			DeviceID:           "phone-1",
			TruncatedForUpload: true,
			Health: &model.HealthSnapshot{
				Steps:              &steps,
				ExerciseSessions:   &exerciseSessions,
				ExerciseMinutes:    &exerciseMinutes,
				GrantedRecordTypes: []string{"StepsRecord"},
			},
		}},
	})
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(response.String(), "1 sessions, 30 min") ||
		!strings.Contains(response.String(), "Compacted for upload") {
		t.Fatalf("dashboard missing health values: %s", response.String())
	}
}

func TestCollectRejectsTooManyGNSSSatellites(t *testing.T) {
	dataStore, err := store.Open(filepath.Join(t.TempDir(), "data.jsonl"))
	if err != nil {
		t.Fatal(err)
	}
	defer dataStore.Close()
	server, err := New(dataStore, "collect-secret", nil)
	if err != nil {
		t.Fatal(err)
	}
	payload, err := json.Marshal(model.Collection{
		DeviceID: "phone-1",
		GNSS: &model.GNSSSnapshot{
			Satellites: make([]model.GNSSSatelliteSnapshot, 129),
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	request := httptest.NewRequest(http.MethodPost, "/api/collect", bytes.NewReader(payload))
	request.Header.Set("Content-Type", "application/json")
	request.Header.Set("Authorization", "Bearer collect-secret")
	response := httptest.NewRecorder()
	server.collect(response, request)
	if response.Code != http.StatusBadRequest ||
		!strings.Contains(response.Body.String(), "limited to 128") {
		t.Fatalf("unexpected response: %d: %s", response.Code, response.Body.String())
	}
}

func TestValidateCollectionRejectsInvalidLocationBounds(t *testing.T) {
	tests := []struct {
		name       string
		collection model.Collection
		reason     string
	}{
		{
			name: "coordinates",
			collection: model.Collection{
				DeviceID: "phone-1",
				Location: &model.LocationSnapshot{Latitude: 91, Longitude: 0},
			},
			reason: "out of range",
		},
		{
			name: "history",
			collection: model.Collection{
				DeviceID:        "phone-1",
				LocationHistory: make([]model.LocationSnapshot, 101),
			},
			reason: "limited to 100",
		},
		{
			name: "providers",
			collection: model.Collection{
				DeviceID: "phone-1",
				LocationStatus: &model.LocationStatusSnapshot{
					Providers: make([]model.LocationProviderSnapshot, 65),
				},
			},
			reason: "limited to 64",
		},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			err := validateCollection(&test.collection)
			if err == nil || !strings.Contains(err.Error(), test.reason) {
				t.Fatalf("unexpected validation result: %v", err)
			}
		})
	}
}
