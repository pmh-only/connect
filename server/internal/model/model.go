package model

import "encoding/json"

type Collection struct {
	DeviceID           string                 `json:"deviceId"`
	DeviceName         string                 `json:"deviceName"`
	CollectedAt        int64                  `json:"collectedAt"`
	ReceivedAt         int64                  `json:"receivedAt"`
	TruncatedForUpload bool                   `json:"truncatedForUpload,omitempty"`
	Health             *HealthSnapshot        `json:"health,omitempty"`
	SMSMessages        []SMSSnapshot          `json:"smsMessages,omitempty"`
	Notifications      []NotificationSnapshot `json:"notifications,omitempty"`
	Battery            *BatterySnapshot       `json:"battery,omitempty"`
	Location           *LocationSnapshot      `json:"location,omitempty"`
}

type HealthSnapshot struct {
	Steps                         *int64                    `json:"steps,omitempty"`
	DistanceKilometers            *float64                  `json:"distanceKilometers,omitempty"`
	ActiveCalories                *float64                  `json:"activeCalories,omitempty"`
	ExerciseSessions              *int                      `json:"exerciseSessions,omitempty"`
	TotalCalories                 *float64                  `json:"totalCalories,omitempty"`
	ElevationGainedMeters         *float64                  `json:"elevationGainedMeters,omitempty"`
	FloorsClimbed                 *float64                  `json:"floorsClimbed,omitempty"`
	ExerciseMinutes               *int64                    `json:"exerciseMinutes,omitempty"`
	SleepMinutes                  *int64                    `json:"sleepMinutes,omitempty"`
	AverageHeartRateBPM           *int64                    `json:"averageHeartRateBpm,omitempty"`
	MinimumHeartRateBPM           *int64                    `json:"minimumHeartRateBpm,omitempty"`
	MaximumHeartRateBPM           *int64                    `json:"maximumHeartRateBpm,omitempty"`
	RestingHeartRateBPM           *int64                    `json:"restingHeartRateBpm,omitempty"`
	WeightKilograms               *float64                  `json:"weightKilograms,omitempty"`
	BodyFatPercentage             *float64                  `json:"bodyFatPercentage,omitempty"`
	OxygenSaturationPercentage    *float64                  `json:"oxygenSaturationPercentage,omitempty"`
	Records                       []HealthRecordSnapshot    `json:"records,omitempty"`
	MedicalResources              []MedicalResourceSnapshot `json:"medicalResources,omitempty"`
	SupportedRecordTypes          []string                  `json:"supportedRecordTypes,omitempty"`
	GrantedRecordTypes            []string                  `json:"grantedRecordTypes,omitempty"`
	SupportedMedicalResourceTypes []int                     `json:"supportedMedicalResourceTypes,omitempty"`
	GrantedMedicalResourceTypes   []int                     `json:"grantedMedicalResourceTypes,omitempty"`
	FailedRecordTypes             []string                  `json:"failedRecordTypes,omitempty"`
	FailedMedicalResourceTypes    []int                     `json:"failedMedicalResourceTypes,omitempty"`
	CollectedAt                   int64                     `json:"collectedAt"`
}

type HealthRecordSnapshot struct {
	ID                     string          `json:"id"`
	RecordType             string          `json:"recordType"`
	StartTime              int64           `json:"startTime"`
	EndTime                *int64          `json:"endTime,omitempty"`
	StartZoneOffsetSeconds *int            `json:"startZoneOffsetSeconds,omitempty"`
	EndZoneOffsetSeconds   *int            `json:"endZoneOffsetSeconds,omitempty"`
	LastModifiedTime       int64           `json:"lastModifiedTime"`
	DataOrigin             string          `json:"dataOrigin"`
	RecordingMethod        int             `json:"recordingMethod"`
	ClientRecordID         *string         `json:"clientRecordId,omitempty"`
	ClientRecordVersion    int64           `json:"clientRecordVersion"`
	DeviceType             *int            `json:"deviceType,omitempty"`
	DeviceManufacturer     *string         `json:"deviceManufacturer,omitempty"`
	DeviceModel            *string         `json:"deviceModel,omitempty"`
	Data                   json.RawMessage `json:"data"`
}

type MedicalResourceSnapshot struct {
	MedicalResourceType int    `json:"medicalResourceType"`
	DataSourceID        string `json:"dataSourceId"`
	FHIRResourceType    int    `json:"fhirResourceType"`
	FHIRResourceID      string `json:"fhirResourceId"`
	FHIRVersion         string `json:"fhirVersion"`
	FHIRJSON            string `json:"fhirJson"`
	FHIRJSONTruncated   bool   `json:"fhirJsonTruncated"`
}

type SMSSnapshot struct {
	ID        int64  `json:"id"`
	Address   string `json:"address"`
	Body      string `json:"body"`
	Timestamp int64  `json:"timestamp"`
	Type      int    `json:"type"`
}

type NotificationSnapshot struct {
	Key         string `json:"key"`
	PackageName string `json:"packageName"`
	Title       string `json:"title"`
	Text        string `json:"text"`
	Timestamp   int64  `json:"timestamp"`
}

type BatterySnapshot struct {
	LevelPercent       int     `json:"levelPercent"`
	Charging           bool    `json:"charging"`
	TemperatureCelsius float64 `json:"temperatureCelsius"`
	Plugged            int     `json:"plugged"`
}

type LocationSnapshot struct {
	Latitude             float64 `json:"latitude"`
	Longitude            float64 `json:"longitude"`
	AccuracyMeters       float64 `json:"accuracyMeters"`
	AltitudeMeters       float64 `json:"altitudeMeters"`
	SpeedMetersPerSecond float64 `json:"speedMetersPerSecond"`
	Provider             string  `json:"provider"`
	Timestamp            int64   `json:"timestamp"`
}
