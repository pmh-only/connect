package model

type Collection struct {
	DeviceID      string                 `json:"deviceId"`
	DeviceName    string                 `json:"deviceName"`
	CollectedAt   int64                  `json:"collectedAt"`
	ReceivedAt    int64                  `json:"receivedAt"`
	Health        *HealthSnapshot        `json:"health,omitempty"`
	SMSMessages   []SMSSnapshot          `json:"smsMessages,omitempty"`
	Notifications []NotificationSnapshot `json:"notifications,omitempty"`
	Battery       *BatterySnapshot       `json:"battery,omitempty"`
	Location      *LocationSnapshot      `json:"location,omitempty"`
}

type HealthSnapshot struct {
	Steps              int64   `json:"steps"`
	DistanceKilometers float64 `json:"distanceKilometers"`
	ActiveCalories     float64 `json:"activeCalories"`
	ExerciseSessions   int     `json:"exerciseSessions"`
	CollectedAt        int64   `json:"collectedAt"`
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
