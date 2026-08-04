package web

import (
	"bytes"
	"encoding/json"
	"fmt"
	"math"
	"net/url"
	"sort"
	"strconv"
	"strings"
	"time"

	"connect/server/internal/auth"
	"connect/server/internal/model"
)

type dashboardData struct {
	User            auth.User
	CSRFToken       string
	Devices         []model.Collection
	DeviceRows      []deviceRow
	Selected        *model.Collection
	SelectedID      string
	Page            string
	PagePath        string
	PageTitle       string
	PageDescription string
	Overview        overviewView
	Health          healthView
	Location        locationView
	Communications  communicationsView
	Device          deviceView
}

type overviewView struct {
	TotalDevices     int
	ReportingDevices int
	AverageBattery   string
	CoveragePercent  int
	AttentionCount   int
	HealthRecords    int
	Events           int
	LocationFixes    int
	Categories       []categoryCoverage
}

type categoryCoverage struct {
	Name        string
	Description string
	Path        string
	Count       int
	Total       int
	Percent     int
	Tone        string
}

type deviceRow struct {
	ID             string
	Name           string
	Initials       string
	ReceivedAt     int64
	Status         string
	StatusClass    string
	Battery        string
	BatteryPercent int
	Charging       bool
	Location       string
	HealthRecords  int
	Events         int
	Coverage       int
	Completeness   string
	NeedsAttention bool
}

type healthView struct {
	Available       bool
	Query           string
	TypeFilter      string
	Metrics         []metricCard
	Goals           []progressMetric
	Heart           heartRange
	Records         []healthRecordRow
	RecordTypes     []string
	RecordMix       []distributionBar
	Permissions     []permissionRow
	Medical         []medicalRow
	TotalRecords    int
	FilteredRecords int
	GrantedCount    int
	SupportedCount  int
	FailedCount     int
}

type metricCard struct {
	Label string
	Value string
	Note  string
	Tone  string
}

type progressMetric struct {
	Label   string
	Value   string
	Target  string
	Percent int
	Tone    string
}

type heartRange struct {
	Available bool
	Minimum   string
	Average   string
	Maximum   string
	MinPos    int
	AvgPos    int
	MaxPos    int
}

type healthRecordRow struct {
	Type      string
	TypeLabel string
	Category  string
	StartedAt int64
	Origin    string
	Method    string
	Data      string
}

type distributionBar struct {
	Label   string
	Value   int
	Percent int
}

type permissionRow struct {
	Name   string
	Label  string
	Status string
	Class  string
}

type medicalRow struct {
	Type       string
	FHIRType   int
	ResourceID string
	Version    string
	Truncated  bool
}

type locationView struct {
	Available          bool
	ProviderFilter     string
	QualityFilter      string
	SignalFilter       string
	Metrics            []metricCard
	Address            string
	MapPath            string
	MapPoints          []mapPoint
	MapCaption         string
	History            []locationRow
	Providers          []providerRow
	ProviderOptions    []string
	Satellites         []satelliteRow
	Constellations     []distributionBar
	ReportedSatellites int
	UsedSatellites     int
	AverageSignal      string
}

type mapPoint struct {
	X       float64
	Y       float64
	Latest  bool
	Tooltip string
}

type locationRow struct {
	Timestamp   int64
	Provider    string
	Coordinates string
	Accuracy    string
	Altitude    string
	Speed       string
	Age         string
	IsMock      bool
	Quality     string
}

type providerRow struct {
	Name         string
	Status       string
	StatusClass  string
	Accuracy     string
	Power        string
	Capabilities string
}

type satelliteRow struct {
	SVID          int
	Constellation string
	Signal        string
	SignalPercent int
	Elevation     string
	Azimuth       string
	Frequency     string
	UsedInFix     bool
	OrbitData     string
}

type communicationsView struct {
	Available         bool
	Query             string
	ChannelFilter     string
	TotalEvents       int
	SMSCount          int
	NotificationCount int
	SourceCount       int
	FilteredCount     int
	Events            []communicationRow
	Sources           []distributionBar
	Activity          []distributionBar
}

type communicationRow struct {
	Timestamp    int64
	Channel      string
	ChannelClass string
	Source       string
	Title        string
	Message      string
	Direction    string
}

type deviceView struct {
	Available  bool
	Metrics    []metricCard
	Coverage   []deviceCoverageRow
	Issues     []string
	Providers  int
	Satellites int
}

type deviceCoverageRow struct {
	Name        string
	Description string
	Available   bool
	Detail      string
	Path        string
}

func buildDashboardData(user auth.User, csrfToken, page, pagePath string, devices []model.Collection, query url.Values) dashboardData {
	selectedID := strings.TrimSpace(query.Get("device"))
	var selected *model.Collection
	for index := range devices {
		if devices[index].DeviceID == selectedID {
			selected = &devices[index]
			break
		}
	}
	if selected == nil && len(devices) > 0 {
		selected = &devices[0]
		selectedID = devices[0].DeviceID
	}

	title, description := pageMetadata(page)
	data := dashboardData{
		User: user, CSRFToken: csrfToken, Devices: devices, Selected: selected,
		SelectedID: selectedID, Page: page, PagePath: pagePath, PageTitle: title,
		PageDescription: description,
	}
	data.DeviceRows = buildDeviceRows(devices)
	data.Overview = buildOverview(devices, data.DeviceRows)
	data.Health = buildHealthView(selected, query)
	data.Location = buildLocationView(selected, query)
	data.Communications = buildCommunicationsView(selected, query)
	data.Device = buildDeviceView(selected)
	return data
}

func pageMetadata(page string) (string, string) {
	switch page {
	case "health":
		return "Health intelligence", "Daily outcomes, detailed records, permissions, and clinical resources."
	case "location":
		return "Location & GNSS", "Position quality, movement fixes, providers, and satellite signal telemetry."
	case "communications":
		return "Communications", "A searchable event stream of messages and device notifications."
	case "devices":
		return "Device operations", "Fleet status, collection coverage, battery condition, and ingest health."
	default:
		return "Command overview", "Current posture across every connected device and telemetry category."
	}
}

func buildOverview(devices []model.Collection, rows []deviceRow) overviewView {
	view := overviewView{TotalDevices: len(devices)}
	batteryTotal, batteryCount, availableCategories := 0, 0, 0
	categoryCounts := make(map[string]int)
	for index, device := range devices {
		if rows[index].StatusClass == "live" {
			view.ReportingDevices++
		}
		if rows[index].NeedsAttention {
			view.AttentionCount++
		}
		if device.Battery != nil {
			batteryTotal += device.Battery.LevelPercent
			batteryCount++
			categoryCounts["Power"]++
		}
		if device.Health != nil {
			categoryCounts["Health"]++
			view.HealthRecords += len(device.Health.Records) + len(device.Health.MedicalResources)
		}
		if device.Location != nil || len(device.LocationHistory) > 0 {
			categoryCounts["Location"]++
			view.LocationFixes += len(device.LocationHistory)
		}
		if len(device.SMSMessages) > 0 || len(device.Notifications) > 0 {
			categoryCounts["Communications"]++
		}
		if device.GNSS != nil {
			categoryCounts["GNSS"]++
		}
		view.Events += len(device.SMSMessages) + len(device.Notifications)
	}
	if batteryCount > 0 {
		view.AverageBattery = fmt.Sprintf("%d%%", batteryTotal/batteryCount)
	} else {
		view.AverageBattery = "No data"
	}
	for _, count := range categoryCounts {
		availableCategories += count
	}
	if len(devices) > 0 {
		view.CoveragePercent = percent(availableCategories, len(devices)*5)
	}
	categories := []struct {
		name, description, path, tone string
	}{
		{"Health", "Summaries and structured records", "/health", "violet"},
		{"Location", "Current and recent position fixes", "/location", "cyan"},
		{"Communications", "SMS and notification events", "/communications", "amber"},
		{"GNSS", "Live satellite status", "/location", "green"},
		{"Power", "Battery condition and charging", "/devices", "coral"},
	}
	for _, category := range categories {
		view.Categories = append(view.Categories, categoryCoverage{
			Name: category.name, Description: category.description, Path: category.path,
			Count: categoryCounts[category.name], Total: len(devices),
			Percent: percent(categoryCounts[category.name], len(devices)), Tone: category.tone,
		})
	}
	return view
}

func buildDeviceRows(devices []model.Collection) []deviceRow {
	rows := make([]deviceRow, 0, len(devices))
	for _, device := range devices {
		name := device.DeviceName
		if name == "" {
			name = "Unnamed Android device"
		}
		status, statusClass := collectionStatus(device.ReceivedAt)
		row := deviceRow{
			ID: device.DeviceID, Name: name, Initials: initials(name), ReceivedAt: device.ReceivedAt,
			Status: status, StatusClass: statusClass,
			Location: locationLabel(device.Location), Completeness: "Complete",
		}
		if device.Battery != nil {
			row.BatteryPercent = clamp(device.Battery.LevelPercent, 0, 100)
			row.Battery = fmt.Sprintf("%d%%", device.Battery.LevelPercent)
			row.Charging = device.Battery.Charging
		} else {
			row.Battery = "No data"
		}
		if device.Health != nil {
			row.HealthRecords = len(device.Health.Records) + len(device.Health.MedicalResources)
			if len(device.Health.FailedRecordTypes)+len(device.Health.FailedMedicalResourceTypes) > 0 {
				row.Completeness = fmt.Sprintf("%d collection failures", len(device.Health.FailedRecordTypes)+len(device.Health.FailedMedicalResourceTypes))
				row.NeedsAttention = true
			}
		}
		row.Events = len(device.SMSMessages) + len(device.Notifications)
		row.Coverage = deviceCoveragePercent(device)
		if device.TruncatedForUpload {
			row.Completeness = "Compacted for upload"
			row.NeedsAttention = true
		}
		if statusClass != "live" {
			row.NeedsAttention = true
		}
		rows = append(rows, row)
	}
	return rows
}

func buildHealthView(device *model.Collection, query url.Values) healthView {
	view := healthView{Query: strings.TrimSpace(query.Get("q")), TypeFilter: strings.TrimSpace(query.Get("type"))}
	if device == nil || device.Health == nil {
		return view
	}
	view.Available = true
	health := device.Health
	view.TotalRecords = len(health.Records)
	view.GrantedCount = len(health.GrantedRecordTypes) + len(health.GrantedMedicalResourceTypes)
	view.SupportedCount = len(health.SupportedRecordTypes) + len(health.SupportedMedicalResourceTypes)
	view.FailedCount = len(health.FailedRecordTypes) + len(health.FailedMedicalResourceTypes)
	view.Metrics = []metricCard{
		{Label: "Steps today", Value: int64Value(health.Steps, "No data"), Note: "Daily movement", Tone: "violet"},
		{Label: "Active energy", Value: floatValue(health.ActiveCalories, "%.0f kcal", "No data"), Note: "Activity calories", Tone: "coral"},
		{Label: "Sleep", Value: durationValue(health.SleepMinutes), Note: "Today", Tone: "cyan"},
		{Label: "Average heart rate", Value: int64UnitValue(health.AverageHeartRateBPM, " bpm", "No data"), Note: "Across samples", Tone: "green"},
	}
	view.Goals = []progressMetric{
		progressInt64("Steps", health.Steps, 10000, "10,000 steps", "violet"),
		progressFloat("Active calories", health.ActiveCalories, 600, "600 kcal", "coral"),
		progressInt64("Exercise", health.ExerciseMinutes, 30, "30 min", "green"),
		progressInt64("Sleep", health.SleepMinutes, 480, "8 hours", "cyan"),
	}
	view.Heart = buildHeartRange(health)

	recordTypeSet := make(map[string]bool)
	categoryCounts := make(map[string]int)
	queryText := strings.ToLower(view.Query)
	for _, record := range health.Records {
		recordTypeSet[record.RecordType] = true
		category := healthCategory(record.RecordType)
		categoryCounts[category]++
		if view.TypeFilter != "" && record.RecordType != view.TypeFilter {
			continue
		}
		data := compactData(record.Data)
		if queryText != "" && !strings.Contains(strings.ToLower(record.RecordType+" "+record.DataOrigin+" "+data), queryText) {
			continue
		}
		view.Records = append(view.Records, healthRecordRow{
			Type: record.RecordType, TypeLabel: humanizeIdentifier(strings.TrimSuffix(record.RecordType, "Record")),
			Category: category, StartedAt: record.StartTime,
			Origin: record.DataOrigin, Method: recordingMethod(record.RecordingMethod), Data: data,
		})
	}
	sort.Slice(view.Records, func(i, j int) bool { return view.Records[i].StartedAt > view.Records[j].StartedAt })
	view.FilteredRecords = len(view.Records)
	for recordType := range recordTypeSet {
		view.RecordTypes = append(view.RecordTypes, recordType)
	}
	sort.Strings(view.RecordTypes)
	view.RecordMix = distributionFromCounts(categoryCounts)

	granted := stringSet(health.GrantedRecordTypes)
	failed := stringSet(health.FailedRecordTypes)
	permissionTypes := append([]string(nil), health.SupportedRecordTypes...)
	for value := range granted {
		if !contains(permissionTypes, value) {
			permissionTypes = append(permissionTypes, value)
		}
	}
	sort.Strings(permissionTypes)
	for _, recordType := range permissionTypes {
		status, class := "Not granted", "muted"
		if granted[recordType] {
			status, class = "Granted", "good"
		}
		if failed[recordType] {
			status, class = "Read failed", "danger"
		}
		view.Permissions = append(view.Permissions, permissionRow{
			Name: recordType, Label: humanizeIdentifier(strings.TrimSuffix(recordType, "Record")), Status: status, Class: class,
		})
	}
	for _, resource := range health.MedicalResources {
		view.Medical = append(view.Medical, medicalRow{
			Type: medicalResourceTypeName(resource.MedicalResourceType), FHIRType: resource.FHIRResourceType,
			ResourceID: resource.FHIRResourceID,
			Version:    resource.FHIRVersion, Truncated: resource.FHIRJSONTruncated,
		})
	}
	return view
}

func buildHeartRange(health *model.HealthSnapshot) heartRange {
	if health.MinimumHeartRateBPM == nil && health.AverageHeartRateBPM == nil && health.MaximumHeartRateBPM == nil {
		return heartRange{}
	}
	value := func(pointer *int64, fallback int64) int64 {
		if pointer == nil {
			return fallback
		}
		return *pointer
	}
	average := value(health.AverageHeartRateBPM, 0)
	minimum := value(health.MinimumHeartRateBPM, average)
	maximum := value(health.MaximumHeartRateBPM, average)
	position := func(bpm int64) int { return clamp(int(math.Round(float64(bpm-40)/1.6)), 0, 100) }
	return heartRange{
		Available: true, Minimum: fmt.Sprintf("%d", minimum), Average: fmt.Sprintf("%d", average), Maximum: fmt.Sprintf("%d", maximum),
		MinPos: position(minimum), AvgPos: position(average), MaxPos: position(maximum),
	}
}

func buildLocationView(device *model.Collection, query url.Values) locationView {
	view := locationView{
		ProviderFilter: strings.TrimSpace(query.Get("provider")),
		QualityFilter:  strings.TrimSpace(query.Get("quality")),
		SignalFilter:   strings.TrimSpace(query.Get("signal")),
	}
	if device == nil {
		return view
	}
	view.Available = device.Location != nil || len(device.LocationHistory) > 0 || device.LocationStatus != nil || device.GNSS != nil
	if device.Location != nil {
		location := device.Location
		view.Metrics = []metricCard{
			{Label: "Coordinates", Value: fmt.Sprintf("%.5f, %.5f", location.Latitude, location.Longitude), Note: valueOr(location.Provider, "Unknown provider"), Tone: "violet"},
			{Label: "Horizontal accuracy", Value: floatValue(location.AccuracyMeters, "±%.1f m", "Unknown"), Note: locationQuality(location), Tone: "cyan"},
			{Label: "Speed", Value: speedValue(location.SpeedMetersPerSecond), Note: bearingValue(location.BearingDegrees), Tone: "green"},
			{Label: "Altitude", Value: floatValue(location.AltitudeMeters, "%.1f m", "No data"), Note: verticalAccuracy(location.VerticalAccuracyMeters), Tone: "coral"},
		}
		view.Address = addressLabel(location.Address)
	}

	locations := append([]model.LocationSnapshot(nil), device.LocationHistory...)
	if len(locations) == 0 && device.Location != nil {
		locations = append(locations, *device.Location)
	}
	providerSet := make(map[string]bool)
	for _, location := range locations {
		providerSet[valueOr(location.Provider, "unknown")] = true
		if view.ProviderFilter != "" && location.Provider != view.ProviderFilter && !(view.ProviderFilter == "unknown" && location.Provider == "") {
			continue
		}
		if view.QualityFilter == "precise" && (location.AccuracyMeters == nil || *location.AccuracyMeters > 20) {
			continue
		}
		if view.QualityFilter == "mock" && !location.IsMock {
			continue
		}
		if view.QualityFilter == "complete" && (location.IsComplete == nil || !*location.IsComplete) {
			continue
		}
		view.History = append(view.History, locationRow{
			Timestamp: location.Timestamp, Provider: valueOr(location.Provider, "Unknown"),
			Coordinates: fmt.Sprintf("%.5f, %.5f", location.Latitude, location.Longitude),
			Accuracy:    floatValue(location.AccuracyMeters, "±%.1f m", "Unknown"),
			Altitude:    floatValue(location.AltitudeMeters, "%.1f m", "Unknown"),
			Speed:       speedValue(location.SpeedMetersPerSecond), Age: ageValue(location.AgeAtReceiptMillis),
			IsMock: location.IsMock, Quality: locationQuality(&location),
		})
	}
	for provider := range providerSet {
		view.ProviderOptions = append(view.ProviderOptions, provider)
	}
	sort.Strings(view.ProviderOptions)
	sort.Slice(view.History, func(i, j int) bool { return view.History[i].Timestamp > view.History[j].Timestamp })
	view.MapPath, view.MapPoints, view.MapCaption = buildLocationMap(locations)

	if device.LocationStatus != nil {
		for _, provider := range device.LocationStatus.Providers {
			capabilities := make([]string, 0, 3)
			if booleanValue(provider.SupportsAltitude) {
				capabilities = append(capabilities, "altitude")
			}
			if booleanValue(provider.SupportsBearing) {
				capabilities = append(capabilities, "bearing")
			}
			if booleanValue(provider.SupportsSpeed) {
				capabilities = append(capabilities, "speed")
			}
			status, class := "Disabled", "muted"
			if provider.Enabled {
				status, class = "Enabled", "good"
			}
			view.Providers = append(view.Providers, providerRow{
				Name: provider.Name, Status: status, StatusClass: class,
				Accuracy: providerAccuracy(provider.Accuracy), Power: providerPower(provider.PowerUsage),
				Capabilities: strings.Join(capabilities, ", "),
			})
		}
	}
	if device.GNSS != nil {
		view.ReportedSatellites = device.GNSS.ReportedSatelliteCount
		constellationCounts := make(map[string]int)
		signalTotal := 0.0
		for _, satellite := range device.GNSS.Satellites {
			constellation := constellationName(satellite.ConstellationType)
			constellationCounts[constellation]++
			signalTotal += satellite.CN0DBHz
			if satellite.UsedInFix {
				view.UsedSatellites++
			}
			if view.SignalFilter == "used" && !satellite.UsedInFix {
				continue
			}
			if view.SignalFilter == "strong" && satellite.CN0DBHz < 30 {
				continue
			}
			view.Satellites = append(view.Satellites, satelliteRow{
				SVID: satellite.SVID, Constellation: constellation,
				Signal: fmt.Sprintf("%.1f dB-Hz", satellite.CN0DBHz), SignalPercent: clamp(int(math.Round(satellite.CN0DBHz*2)), 0, 100),
				Elevation: fmt.Sprintf("%.0f°", satellite.ElevationDegrees), Azimuth: fmt.Sprintf("%.0f°", satellite.AzimuthDegrees),
				Frequency: frequencyValue(satellite.CarrierFrequencyHz), UsedInFix: satellite.UsedInFix,
				OrbitData: orbitData(satellite),
			})
		}
		if len(device.GNSS.Satellites) > 0 {
			view.AverageSignal = fmt.Sprintf("%.1f dB-Hz", signalTotal/float64(len(device.GNSS.Satellites)))
		}
		view.Constellations = distributionFromCounts(constellationCounts)
		sort.Slice(view.Satellites, func(i, j int) bool { return view.Satellites[i].SignalPercent > view.Satellites[j].SignalPercent })
	}
	return view
}

func buildLocationMap(locations []model.LocationSnapshot) (string, []mapPoint, string) {
	if len(locations) == 0 {
		return "", nil, "No movement fixes collected"
	}
	sort.Slice(locations, func(i, j int) bool { return locations[i].Timestamp < locations[j].Timestamp })
	minLatitude, maxLatitude := locations[0].Latitude, locations[0].Latitude
	minLongitude, maxLongitude := locations[0].Longitude, locations[0].Longitude
	for _, location := range locations[1:] {
		minLatitude = math.Min(minLatitude, location.Latitude)
		maxLatitude = math.Max(maxLatitude, location.Latitude)
		minLongitude = math.Min(minLongitude, location.Longitude)
		maxLongitude = math.Max(maxLongitude, location.Longitude)
	}
	latitudeSpan, longitudeSpan := maxLatitude-minLatitude, maxLongitude-minLongitude
	if latitudeSpan < 0.0001 {
		latitudeSpan = 0.0001
	}
	if longitudeSpan < 0.0001 {
		longitudeSpan = 0.0001
	}
	points := make([]mapPoint, 0, len(locations))
	pathParts := make([]string, 0, len(locations))
	for index, location := range locations {
		x := 42 + ((location.Longitude-minLongitude)/longitudeSpan)*716
		y := 308 - ((location.Latitude-minLatitude)/latitudeSpan)*260
		points = append(points, mapPoint{
			X: x, Y: y, Latest: index == len(locations)-1,
			Tooltip: fmt.Sprintf("%s · %.5f, %.5f", valueOr(location.Provider, "Unknown"), location.Latitude, location.Longitude),
		})
		pathParts = append(pathParts, fmt.Sprintf("%.1f,%.1f", x, y))
	}
	caption := fmt.Sprintf("%d fixes · %.5f–%.5f N · %.5f–%.5f E", len(locations), minLatitude, maxLatitude, minLongitude, maxLongitude)
	return strings.Join(pathParts, " "), points, caption
}

func buildCommunicationsView(device *model.Collection, query url.Values) communicationsView {
	view := communicationsView{Query: strings.TrimSpace(query.Get("q")), ChannelFilter: strings.TrimSpace(query.Get("channel"))}
	if device == nil {
		return view
	}
	view.SMSCount = len(device.SMSMessages)
	view.NotificationCount = len(device.Notifications)
	view.TotalEvents = view.SMSCount + view.NotificationCount
	view.Available = view.TotalEvents > 0
	sourceCounts := make(map[string]int)
	activityCounts := map[string]int{"00–05": 0, "06–11": 0, "12–17": 0, "18–23": 0}
	queryText := strings.ToLower(view.Query)
	add := func(row communicationRow) {
		sourceCounts[row.Source]++
		hour := time.UnixMilli(row.Timestamp).Hour()
		switch {
		case hour < 6:
			activityCounts["00–05"]++
		case hour < 12:
			activityCounts["06–11"]++
		case hour < 18:
			activityCounts["12–17"]++
		default:
			activityCounts["18–23"]++
		}
		if view.ChannelFilter != "" && strings.ToLower(row.Channel) != view.ChannelFilter {
			return
		}
		if queryText != "" && !strings.Contains(strings.ToLower(row.Source+" "+row.Title+" "+row.Message), queryText) {
			return
		}
		view.Events = append(view.Events, row)
	}
	for _, message := range device.SMSMessages {
		direction := "Received"
		if message.Type == 2 {
			direction = "Sent"
		}
		add(communicationRow{Timestamp: message.Timestamp, Channel: "SMS", ChannelClass: "sms", Source: valueOr(message.Address, "Unknown sender"), Title: direction + " message", Message: message.Body, Direction: direction})
	}
	for _, notification := range device.Notifications {
		add(communicationRow{Timestamp: notification.Timestamp, Channel: "Notification", ChannelClass: "notification", Source: valueOr(notification.PackageName, "Unknown app"), Title: valueOr(notification.Title, "Untitled notification"), Message: notification.Text, Direction: "Received"})
	}
	sort.Slice(view.Events, func(i, j int) bool { return view.Events[i].Timestamp > view.Events[j].Timestamp })
	view.FilteredCount = len(view.Events)
	view.SourceCount = len(sourceCounts)
	view.Sources = distributionFromCounts(sourceCounts)
	view.Activity = orderedDistribution(activityCounts, []string{"00–05", "06–11", "12–17", "18–23"})
	return view
}

func buildDeviceView(device *model.Collection) deviceView {
	view := deviceView{}
	if device == nil {
		return view
	}
	view.Available = true
	view.Metrics = []metricCard{
		{Label: "Ingest status", Value: statusValue(device.ReceivedAt), Note: "Last server receipt", Tone: "green"},
		{Label: "Battery", Value: batteryValue(device.Battery), Note: batteryNote(device.Battery), Tone: "coral"},
		{Label: "Collection coverage", Value: fmt.Sprintf("%d%%", deviceCoveragePercent(*device)), Note: "Across core categories", Tone: "violet"},
		{Label: "Payload state", Value: completenessValue(*device), Note: "Latest upload", Tone: "cyan"},
	}
	healthDetail := "No health snapshot"
	if device.Health != nil {
		healthDetail = fmt.Sprintf("%d structured · %d medical", len(device.Health.Records), len(device.Health.MedicalResources))
	}
	locationDetail := "No position fix"
	if device.Location != nil {
		locationDetail = fmt.Sprintf("%s · %s", valueOr(device.Location.Provider, "Unknown provider"), floatValue(device.Location.AccuracyMeters, "±%.0f m", "accuracy unknown"))
	}
	communicationDetail := fmt.Sprintf("%d SMS · %d notifications", len(device.SMSMessages), len(device.Notifications))
	gnssDetail := "No GNSS snapshot"
	if device.GNSS != nil {
		gnssDetail = fmt.Sprintf("%d reported · %d retained", device.GNSS.ReportedSatelliteCount, len(device.GNSS.Satellites))
		view.Satellites = len(device.GNSS.Satellites)
	}
	if device.LocationStatus != nil {
		view.Providers = len(device.LocationStatus.Providers)
	}
	view.Coverage = []deviceCoverageRow{
		{Name: "Health", Description: "Daily summary and detailed records", Available: device.Health != nil, Detail: healthDetail, Path: "/health"},
		{Name: "Location", Description: "Position and movement history", Available: device.Location != nil || len(device.LocationHistory) > 0, Detail: locationDetail, Path: "/location"},
		{Name: "Communications", Description: "Messages and notification events", Available: len(device.SMSMessages) > 0 || len(device.Notifications) > 0, Detail: communicationDetail, Path: "/communications"},
		{Name: "GNSS", Description: "Satellite and provider telemetry", Available: device.GNSS != nil, Detail: gnssDetail, Path: "/location"},
		{Name: "Power", Description: "Battery and charging state", Available: device.Battery != nil, Detail: batteryNote(device.Battery), Path: "/devices"},
	}
	if device.TruncatedForUpload {
		view.Issues = append(view.Issues, "The latest payload was compacted to fit the upload limit.")
	}
	if device.Health != nil && len(device.Health.FailedRecordTypes) > 0 {
		view.Issues = append(view.Issues, fmt.Sprintf("%d standard health record types failed to refresh.", len(device.Health.FailedRecordTypes)))
	}
	if device.Health != nil && len(device.Health.FailedMedicalResourceTypes) > 0 {
		view.Issues = append(view.Issues, fmt.Sprintf("%d medical resource types failed to refresh.", len(device.Health.FailedMedicalResourceTypes)))
	}
	if status, class := collectionStatus(device.ReceivedAt); class != "live" {
		view.Issues = append(view.Issues, "Device ingest is "+strings.ToLower(status)+"; the latest data may be stale.")
	}
	return view
}

func collectionStatus(receivedAt int64) (string, string) {
	if receivedAt <= 0 {
		return "Unknown", "muted"
	}
	age := time.Since(time.UnixMilli(receivedAt))
	if age < 0 {
		age = 0
	}
	if age <= 3*time.Minute {
		return "Live", "live"
	}
	if age <= 15*time.Minute {
		return "Delayed", "delayed"
	}
	return "Offline", "offline"
}

func statusValue(receivedAt int64) string { status, _ := collectionStatus(receivedAt); return status }

func deviceCoveragePercent(device model.Collection) int {
	available := 0
	if device.Battery != nil {
		available++
	}
	if device.Health != nil {
		available++
	}
	if device.Location != nil || len(device.LocationHistory) > 0 {
		available++
	}
	if len(device.SMSMessages) > 0 || len(device.Notifications) > 0 {
		available++
	}
	if device.GNSS != nil {
		available++
	}
	return percent(available, 5)
}

func progressInt64(label string, value *int64, target int64, targetLabel, tone string) progressMetric {
	metric := progressMetric{Label: label, Value: "No data", Target: targetLabel, Tone: tone}
	if value != nil {
		metric.Value = commaInt(*value)
		if label == "Exercise" {
			metric.Value += " min"
		}
		if label == "Sleep" {
			metric.Value = durationMinutes(*value)
		}
		metric.Percent = percentInt64(*value, target)
	}
	return metric
}

func progressFloat(label string, value *float64, target float64, targetLabel, tone string) progressMetric {
	metric := progressMetric{Label: label, Value: "No data", Target: targetLabel, Tone: tone}
	if value != nil {
		metric.Value = fmt.Sprintf("%.0f kcal", *value)
		metric.Percent = clamp(int(math.Round(*value/target*100)), 0, 100)
	}
	return metric
}

func distributionFromCounts(counts map[string]int) []distributionBar {
	labels := make([]string, 0, len(counts))
	maxValue := 0
	for label, value := range counts {
		labels = append(labels, label)
		if value > maxValue {
			maxValue = value
		}
	}
	sort.Slice(labels, func(i, j int) bool {
		if counts[labels[i]] == counts[labels[j]] {
			return labels[i] < labels[j]
		}
		return counts[labels[i]] > counts[labels[j]]
	})
	result := make([]distributionBar, 0, len(labels))
	for _, label := range labels {
		result = append(result, distributionBar{Label: label, Value: counts[label], Percent: percent(counts[label], maxValue)})
	}
	return result
}

func orderedDistribution(counts map[string]int, labels []string) []distributionBar {
	maxValue := 0
	for _, value := range counts {
		if value > maxValue {
			maxValue = value
		}
	}
	result := make([]distributionBar, 0, len(labels))
	for _, label := range labels {
		result = append(result, distributionBar{Label: label, Value: counts[label], Percent: percent(counts[label], maxValue)})
	}
	return result
}

func healthCategory(recordType string) string {
	value := strings.ToLower(recordType)
	switch {
	case strings.Contains(value, "sleep"):
		return "Sleep"
	case strings.Contains(value, "heart") || strings.Contains(value, "blood") || strings.Contains(value, "oxygen") || strings.Contains(value, "respiratory") || strings.Contains(value, "temperature"):
		return "Vitals"
	case strings.Contains(value, "weight") || strings.Contains(value, "height") || strings.Contains(value, "body") || strings.Contains(value, "bone") || strings.Contains(value, "lean"):
		return "Body"
	case strings.Contains(value, "nutrition") || strings.Contains(value, "hydration"):
		return "Nutrition"
	case strings.Contains(value, "menstruation") || strings.Contains(value, "ovulation") || strings.Contains(value, "cervical") || strings.Contains(value, "intermenstrual") || strings.Contains(value, "sexual"):
		return "Cycle"
	case strings.Contains(value, "mindfulness"):
		return "Mindfulness"
	case strings.Contains(value, "step") || strings.Contains(value, "distance") || strings.Contains(value, "calories") || strings.Contains(value, "exercise") || strings.Contains(value, "elevation") || strings.Contains(value, "floors") || strings.Contains(value, "wheelchair") || strings.Contains(value, "power") || strings.Contains(value, "speed"):
		return "Activity"
	default:
		return "Other"
	}
}

func medicalResourceTypeName(resourceType int) string {
	switch resourceType {
	case 1:
		return "Vaccines"
	case 2:
		return "Allergies & intolerances"
	case 3:
		return "Pregnancy"
	case 4:
		return "Social history"
	case 5:
		return "Vital signs"
	case 6:
		return "Laboratory results"
	case 7:
		return "Conditions"
	case 8:
		return "Procedures"
	case 9:
		return "Medications"
	case 10:
		return "Personal details"
	case 11:
		return "Practitioner details"
	case 12:
		return "Visits"
	default:
		return fmt.Sprintf("Medical type %d", resourceType)
	}
}

func humanizeIdentifier(value string) string {
	if value == "" {
		return "Unknown"
	}
	var builder strings.Builder
	for index, character := range value {
		if index > 0 && character >= 'A' && character <= 'Z' {
			previous := rune(value[index-1])
			if previous >= 'a' && previous <= 'z' {
				builder.WriteByte(' ')
			}
		}
		builder.WriteRune(character)
	}
	return builder.String()
}

func compactData(data json.RawMessage) string {
	if len(data) == 0 {
		return "No structured payload"
	}
	var buffer bytes.Buffer
	if err := json.Compact(&buffer, data); err != nil {
		return truncate(string(data), 180)
	}
	return truncate(buffer.String(), 180)
}

func recordingMethod(method int) string {
	switch method {
	case 1:
		return "Active"
	case 2:
		return "Automatic"
	case 3:
		return "Manual"
	default:
		return "Unknown"
	}
}

func stringSet(values []string) map[string]bool {
	result := make(map[string]bool, len(values))
	for _, value := range values {
		result[value] = true
	}
	return result
}

func contains(values []string, value string) bool {
	for _, candidate := range values {
		if candidate == value {
			return true
		}
	}
	return false
}

func locationLabel(location *model.LocationSnapshot) string {
	if location == nil {
		return "No position"
	}
	if location.Address != nil {
		for _, value := range []*string{location.Address.FeatureName, location.Address.Locality, location.Address.AdminArea, location.Address.CountryName} {
			if value != nil && *value != "" {
				return *value
			}
		}
	}
	return fmt.Sprintf("%.4f, %.4f", location.Latitude, location.Longitude)
}

func addressLabel(address *model.LocationAddressSnapshot) string {
	if address == nil {
		return "No resolved address available"
	}
	if len(address.AddressLines) > 0 {
		return strings.Join(address.AddressLines, ", ")
	}
	parts := make([]string, 0, 4)
	for _, value := range []*string{address.FeatureName, address.Thoroughfare, address.Locality, address.CountryName} {
		if value != nil && *value != "" {
			parts = append(parts, *value)
		}
	}
	if len(parts) == 0 {
		return "Address resolved without a display label"
	}
	return strings.Join(parts, ", ")
}

func locationQuality(location *model.LocationSnapshot) string {
	if location.IsMock {
		return "Mock source"
	}
	if location.AccuracyMeters == nil {
		return "Accuracy unknown"
	}
	switch {
	case *location.AccuracyMeters <= 10:
		return "Excellent fix"
	case *location.AccuracyMeters <= 20:
		return "Precise fix"
	case *location.AccuracyMeters <= 100:
		return "Approximate fix"
	default:
		return "Low accuracy"
	}
}

func speedValue(value *float64) string {
	if value == nil {
		return "No data"
	}
	return fmt.Sprintf("%.1f km/h", *value*3.6)
}

func bearingValue(value *float64) string {
	if value == nil {
		return "Bearing unavailable"
	}
	return fmt.Sprintf("%.0f° bearing", *value)
}

func verticalAccuracy(value *float64) string {
	if value == nil {
		return "Vertical accuracy unknown"
	}
	return fmt.Sprintf("±%.1f m vertical", *value)
}

func ageValue(milliseconds int64) string {
	if milliseconds < 1000 {
		return fmt.Sprintf("%d ms", milliseconds)
	}
	return fmt.Sprintf("%.1f s", float64(milliseconds)/1000)
}

func providerAccuracy(value *int) string {
	if value == nil {
		return "Unknown"
	}
	switch *value {
	case 1:
		return "Fine"
	case 2:
		return "Coarse"
	default:
		return fmt.Sprintf("Level %d", *value)
	}
}

func providerPower(value *int) string {
	if value == nil {
		return "Unknown"
	}
	switch *value {
	case 1:
		return "Low"
	case 2:
		return "Medium"
	case 3:
		return "High"
	default:
		return fmt.Sprintf("Level %d", *value)
	}
}

func booleanValue(value *bool) bool { return value != nil && *value }

func constellationName(value int) string {
	switch value {
	case 1:
		return "GPS"
	case 2:
		return "SBAS"
	case 3:
		return "GLONASS"
	case 4:
		return "QZSS"
	case 5:
		return "BeiDou"
	case 6:
		return "Galileo"
	case 7:
		return "NavIC"
	default:
		return "Unknown"
	}
}

func frequencyValue(value *float64) string {
	if value == nil {
		return "Unknown"
	}
	return fmt.Sprintf("%.2f MHz", *value/1_000_000)
}

func orbitData(satellite model.GNSSSatelliteSnapshot) string {
	values := make([]string, 0, 2)
	if satellite.HasEphemerisData {
		values = append(values, "Ephemeris")
	}
	if satellite.HasAlmanacData {
		values = append(values, "Almanac")
	}
	if len(values) == 0 {
		return "None"
	}
	return strings.Join(values, " + ")
}

func batteryValue(battery *model.BatterySnapshot) string {
	if battery == nil {
		return "No data"
	}
	return fmt.Sprintf("%d%%", battery.LevelPercent)
}

func batteryNote(battery *model.BatterySnapshot) string {
	if battery == nil {
		return "No battery snapshot"
	}
	state := "On battery"
	if battery.Charging {
		state = "Charging"
	}
	return fmt.Sprintf("%s · %.1f°C", state, battery.TemperatureCelsius)
}

func completenessValue(device model.Collection) string {
	if device.TruncatedForUpload {
		return "Compacted"
	}
	if device.Health != nil && len(device.Health.FailedRecordTypes)+len(device.Health.FailedMedicalResourceTypes) > 0 {
		return "Partial"
	}
	return "Complete"
}

func int64Value(value *int64, fallback string) string {
	if value == nil {
		return fallback
	}
	return commaInt(*value)
}

func int64UnitValue(value *int64, unit, fallback string) string {
	if value == nil {
		return fallback
	}
	return commaInt(*value) + unit
}

func floatValue(value *float64, format, fallback string) string {
	if value == nil {
		return fallback
	}
	return fmt.Sprintf(format, *value)
}

func durationValue(value *int64) string {
	if value == nil {
		return "No data"
	}
	return durationMinutes(*value)
}

func durationMinutes(value int64) string { return fmt.Sprintf("%dh %02dm", value/60, value%60) }

func commaInt(value int64) string {
	negative := value < 0
	if negative {
		value = -value
	}
	digits := strconv.FormatInt(value, 10)
	for index := len(digits) - 3; index > 0; index -= 3 {
		digits = digits[:index] + "," + digits[index:]
	}
	if negative {
		return "-" + digits
	}
	return digits
}

func valueOr(value, fallback string) string {
	if value == "" {
		return fallback
	}
	return value
}

func percent(value, total int) int {
	if total <= 0 {
		return 0
	}
	return clamp(int(math.Round(float64(value)/float64(total)*100)), 0, 100)
}

func percentInt64(value, total int64) int {
	if total <= 0 {
		return 0
	}
	return clamp(int(math.Round(float64(value)/float64(total)*100)), 0, 100)
}

func clamp(value, minimum, maximum int) int {
	if value < minimum {
		return minimum
	}
	if value > maximum {
		return maximum
	}
	return value
}

func truncate(value string, maximum int) string {
	runes := []rune(value)
	if len(runes) <= maximum {
		return value
	}
	return strings.TrimSpace(string(runes[:maximum])) + "..."
}

func initials(name string) string {
	parts := strings.Fields(name)
	if len(parts) == 0 {
		return "AN"
	}
	if len(parts) == 1 {
		runes := []rune(parts[0])
		if len(runes) > 2 {
			runes = runes[:2]
		}
		return strings.ToUpper(string(runes))
	}
	first := []rune(parts[0])
	last := []rune(parts[len(parts)-1])
	return strings.ToUpper(string(first[0]) + string(last[0]))
}
