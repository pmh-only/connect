package model

import "encoding/json"

type Collection struct {
	DeviceID           string                  `json:"deviceId"`
	DeviceName         string                  `json:"deviceName"`
	CollectedAt        int64                   `json:"collectedAt"`
	ReceivedAt         int64                   `json:"receivedAt"`
	TruncatedForUpload bool                    `json:"truncatedForUpload,omitempty"`
	Health             *HealthSnapshot         `json:"health,omitempty"`
	SMSMessages        []SMSSnapshot           `json:"smsMessages,omitempty"`
	Notifications      []NotificationSnapshot  `json:"notifications,omitempty"`
	Battery            *BatterySnapshot        `json:"battery,omitempty"`
	Location           *LocationSnapshot       `json:"location,omitempty"`
	LocationHistory    []LocationSnapshot      `json:"locationHistory,omitempty"`
	LocationStatus     *LocationStatusSnapshot `json:"locationStatus,omitempty"`
	GNSS               *GNSSSnapshot           `json:"gnss,omitempty"`
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
	Latitude                        float64                  `json:"latitude"`
	Longitude                       float64                  `json:"longitude"`
	AccuracyMeters                  *float64                 `json:"accuracyMeters,omitempty"`
	AltitudeMeters                  *float64                 `json:"altitudeMeters,omitempty"`
	VerticalAccuracyMeters          *float64                 `json:"verticalAccuracyMeters,omitempty"`
	MSLAltitudeMeters               *float64                 `json:"mslAltitudeMeters,omitempty"`
	MSLAltitudeAccuracyMeters       *float64                 `json:"mslAltitudeAccuracyMeters,omitempty"`
	SpeedMetersPerSecond            *float64                 `json:"speedMetersPerSecond,omitempty"`
	SpeedAccuracyMetersPerSecond    *float64                 `json:"speedAccuracyMetersPerSecond,omitempty"`
	BearingDegrees                  *float64                 `json:"bearingDegrees,omitempty"`
	BearingAccuracyDegrees          *float64                 `json:"bearingAccuracyDegrees,omitempty"`
	Provider                        string                   `json:"provider"`
	Timestamp                       int64                    `json:"timestamp"`
	ElapsedRealtimeNanos            int64                    `json:"elapsedRealtimeNanos"`
	ElapsedRealtimeUncertaintyNanos *float64                 `json:"elapsedRealtimeUncertaintyNanos,omitempty"`
	AgeAtReceiptMillis              int64                    `json:"ageAtReceiptMillis"`
	IsMock                          bool                     `json:"isMock"`
	IsComplete                      *bool                    `json:"isComplete,omitempty"`
	Extras                          map[string]string        `json:"extras,omitempty"`
	Address                         *LocationAddressSnapshot `json:"address,omitempty"`
}

type LocationAddressSnapshot struct {
	SourceLocationElapsedRealtimeNanos int64    `json:"sourceLocationElapsedRealtimeNanos"`
	SourceProvider                     string   `json:"sourceProvider"`
	SourceLatitude                     float64  `json:"sourceLatitude"`
	SourceLongitude                    float64  `json:"sourceLongitude"`
	FeatureName                        *string  `json:"featureName,omitempty"`
	Premises                           *string  `json:"premises,omitempty"`
	SubThoroughfare                    *string  `json:"subThoroughfare,omitempty"`
	Thoroughfare                       *string  `json:"thoroughfare,omitempty"`
	SubLocality                        *string  `json:"subLocality,omitempty"`
	Locality                           *string  `json:"locality,omitempty"`
	SubAdminArea                       *string  `json:"subAdminArea,omitempty"`
	AdminArea                          *string  `json:"adminArea,omitempty"`
	PostalCode                         *string  `json:"postalCode,omitempty"`
	CountryCode                        *string  `json:"countryCode,omitempty"`
	CountryName                        *string  `json:"countryName,omitempty"`
	Phone                              *string  `json:"phone,omitempty"`
	URL                                *string  `json:"url,omitempty"`
	Latitude                           *float64 `json:"latitude,omitempty"`
	Longitude                          *float64 `json:"longitude,omitempty"`
	LocaleLanguageTag                  *string  `json:"localeLanguageTag,omitempty"`
	AddressLines                       []string `json:"addressLines,omitempty"`
	ResolvedAt                         int64    `json:"resolvedAt"`
}

type LocationStatusSnapshot struct {
	LocationEnabled       bool                       `json:"locationEnabled"`
	ReportedProviderCount int                        `json:"reportedProviderCount"`
	ProvidersTruncated    bool                       `json:"providersTruncated"`
	Providers             []LocationProviderSnapshot `json:"providers,omitempty"`
	GNSSHardwareYear      *int                       `json:"gnssHardwareYear,omitempty"`
	GNSSHardwareModelName *string                    `json:"gnssHardwareModelName,omitempty"`
	Timestamp             int64                      `json:"timestamp"`
}

type LocationProviderSnapshot struct {
	Name              string `json:"name"`
	Enabled           bool   `json:"enabled"`
	PropertiesKnown   bool   `json:"propertiesKnown"`
	Accuracy          *int   `json:"accuracy,omitempty"`
	PowerUsage        *int   `json:"powerUsage,omitempty"`
	HasMonetaryCost   *bool  `json:"hasMonetaryCost,omitempty"`
	RequiresCell      *bool  `json:"requiresCell,omitempty"`
	RequiresNetwork   *bool  `json:"requiresNetwork,omitempty"`
	RequiresSatellite *bool  `json:"requiresSatellite,omitempty"`
	SupportsAltitude  *bool  `json:"supportsAltitude,omitempty"`
	SupportsBearing   *bool  `json:"supportsBearing,omitempty"`
	SupportsSpeed     *bool  `json:"supportsSpeed,omitempty"`
	LegacyStatus      *int   `json:"legacyStatus,omitempty"`
}

type GNSSSnapshot struct {
	Running                        bool                    `json:"running"`
	TimeToFirstFixMillis           *int                    `json:"timeToFirstFixMillis,omitempty"`
	ReportedSatelliteCount         int                     `json:"reportedSatelliteCount"`
	SatellitesTruncated            bool                    `json:"satellitesTruncated"`
	Satellites                     []GNSSSatelliteSnapshot `json:"satellites,omitempty"`
	CapturedAt                     int64                   `json:"capturedAt"`
	CapturedAtElapsedRealtimeNanos int64                   `json:"capturedAtElapsedRealtimeNanos"`
}

type GNSSSatelliteSnapshot struct {
	ConstellationType               int      `json:"constellationType"`
	SVID                            int      `json:"svid"`
	CN0DBHz                         float64  `json:"cn0DbHz"`
	ElevationDegrees                float64  `json:"elevationDegrees"`
	AzimuthDegrees                  float64  `json:"azimuthDegrees"`
	HasEphemerisData                bool     `json:"hasEphemerisData"`
	HasAlmanacData                  bool     `json:"hasAlmanacData"`
	UsedInFix                       bool     `json:"usedInFix"`
	CarrierFrequencyHz              *float64 `json:"carrierFrequencyHz,omitempty"`
	BasebandCN0DBHz                 *float64 `json:"basebandCn0DbHz,omitempty"`
	CodeType                        *string  `json:"codeType,omitempty"`
	ElapsedRealtimeNanos            *int64   `json:"elapsedRealtimeNanos,omitempty"`
	ElapsedRealtimeUncertaintyNanos *float64 `json:"elapsedRealtimeUncertaintyNanos,omitempty"`
}
