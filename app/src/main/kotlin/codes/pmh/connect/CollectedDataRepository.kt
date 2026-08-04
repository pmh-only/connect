package codes.pmh.connect

import android.Manifest
import android.app.Notification
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.BatteryManager
import android.os.Bundle
import android.provider.Telephony
import android.service.notification.StatusBarNotification
import androidx.core.content.ContextCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseRoute
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class CollectedData(
    val health: HealthSnapshot? = null,
    val smsMessages: List<SmsSnapshot> = emptyList(),
    val notifications: List<NotificationSnapshot> = emptyList(),
    val battery: BatterySnapshot? = null,
    val location: LocationSnapshot? = null,
    val locationHistory: List<LocationSnapshot> = emptyList(),
    val locationStatus: LocationStatusSnapshot? = null,
    val gnss: GnssSnapshot? = null,
)

data class HealthSnapshot(
    val steps: Long?,
    val distanceKilometers: Double?,
    val activeCalories: Double?,
    val exerciseSessions: Int?,
    val collectedAt: Long,
    val totalCalories: Double? = null,
    val elevationGainedMeters: Double? = null,
    val floorsClimbed: Double? = null,
    val exerciseMinutes: Long? = null,
    val sleepMinutes: Long? = null,
    val averageHeartRateBpm: Long? = null,
    val minimumHeartRateBpm: Long? = null,
    val maximumHeartRateBpm: Long? = null,
    val restingHeartRateBpm: Long? = null,
    val weightKilograms: Double? = null,
    val bodyFatPercentage: Double? = null,
    val oxygenSaturationPercentage: Double? = null,
    val records: List<HealthRecordSnapshot> = emptyList(),
    val medicalResources: List<MedicalResourceSnapshot> = emptyList(),
    val supportedRecordTypes: List<String> = emptyList(),
    val grantedRecordTypes: List<String> = emptyList(),
    val supportedMedicalResourceTypes: List<Int> = emptyList(),
    val grantedMedicalResourceTypes: List<Int> = emptyList(),
    val failedRecordTypes: List<String> = emptyList(),
    val failedMedicalResourceTypes: List<Int> = emptyList(),
)

data class SmsSnapshot(
    val id: Long,
    val address: String,
    val body: String,
    val timestamp: Long,
    val type: Int,
)

data class NotificationSnapshot(
    val key: String,
    val packageName: String,
    val title: String,
    val text: String,
    val timestamp: Long,
)

data class BatterySnapshot(
    val levelPercent: Int,
    val charging: Boolean,
    val temperatureCelsius: Double,
    val plugged: Int,
)

object CollectedDataRepository {
    private const val MAX_ITEMS = 100
    private const val GEOCODE_REUSE_DISTANCE_METERS = 100f
    private val LATEST_HEALTH_LOOKBACK = Duration.ofDays(30)
    private val consentedExerciseRoutes = ConcurrentHashMap<String, ExerciseRoute>()

    private val _data = MutableStateFlow(CollectedData())
    val data = _data.asStateFlow()

    fun isHealthAvailable(context: Context): Boolean = runCatching {
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
    }.getOrDefault(false)

    fun requestedHealthPermissions(context: Context): Set<String> {
        if (!isHealthAvailable(context)) return emptySet()

        val client = HealthConnectClient.getOrCreate(context)
        val permissions = HealthRecordCollector.requestedPermissions(client).toMutableSet()
        if (
            client.features.getFeatureStatus(
                HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND,
            ) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
        ) {
            permissions += HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND
        }
        if (
            client.features.getFeatureStatus(
                HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY,
            ) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
        ) {
            permissions += HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY
        }
        return permissions
    }

    suspend fun hasHealthPermissions(context: Context): Boolean {
        if (!isHealthAvailable(context)) return false
        return runCatching {
            val required = requestedHealthPermissions(context)
            HealthConnectClient.getOrCreate(context)
                .permissionController
                .getGrantedPermissions()
                .containsAll(required)
        }.getOrDefault(false)
    }

    suspend fun refresh(context: Context) {
        runCatching { refreshSms(context) }
        runCatching { refreshHealth(context) }
    }

    fun updateBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else 0
        _data.update {
            it.copy(
                battery = BatterySnapshot(
                    levelPercent = percent,
                    charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL,
                    temperatureCelsius =
                        intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0,
                    plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0),
                ),
            )
        }
    }

    fun updateLocation(location: Location): LocationSnapshot? {
        val snapshot = LocationDataCollector.fromLocation(location) ?: return null
        var accepted: LocationSnapshot? = null
        _data.update { current ->
            val existing = current.locationHistory.firstOrNull {
                it.provider == snapshot.provider &&
                    it.elapsedRealtimeNanos == snapshot.elapsedRealtimeNanos
            }
            val reusableAddress = existing?.address ?: current.location
                ?.address
                ?.takeIf { address ->
                    LocationDataCollector.canReuseAddress(
                        address,
                        snapshot,
                        GEOCODE_REUSE_DISTANCE_METERS,
                    )
                }
            val candidate = snapshot.copy(address = reusableAddress)
            val history = (listOf(candidate) + current.locationHistory)
                .distinctBy { "${it.provider}:${it.elapsedRealtimeNanos}" }
                .sortedByDescending(LocationSnapshot::elapsedRealtimeNanos)
                .take(MAX_ITEMS)
            val latest = if (LocationDataCollector.shouldReplace(current.location, candidate)) {
                accepted = candidate
                candidate
            } else {
                current.location
            }
            current.copy(location = latest, locationHistory = history)
        }
        return accepted
    }

    fun updateLocationAddress(address: LocationAddressSnapshot) {
        _data.update { current ->
            val latest = current.location?.let { location ->
                val sameFix = location.elapsedRealtimeNanos ==
                    address.sourceLocationElapsedRealtimeNanos &&
                    location.provider == address.sourceProvider
                val nearby = LocationDataCollector.canReuseAddress(
                    address,
                    location,
                    GEOCODE_REUSE_DISTANCE_METERS,
                )
                if (sameFix || nearby) {
                    location.copy(address = address)
                } else {
                    location
                }
            }
            current.copy(
                location = latest,
                locationHistory = current.locationHistory.map { location ->
                    if (
                        location.elapsedRealtimeNanos == address.sourceLocationElapsedRealtimeNanos &&
                        location.provider == address.sourceProvider
                    ) {
                        location.copy(address = address)
                    } else {
                        location
                    }
                },
            )
        }
    }

    fun updateLocationStatus(status: LocationStatusSnapshot) {
        _data.update { it.copy(locationStatus = status) }
    }

    fun updateGnssStatus(status: GnssSnapshot) {
        _data.update { it.copy(gnss = status) }
    }

    fun updateGnssRunning(running: Boolean) {
        _data.update { current ->
            val now = System.currentTimeMillis()
            val elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
            current.copy(
                gnss = if (running) {
                    GnssSnapshot(
                        running = true,
                        timeToFirstFixMillis = null,
                        reportedSatelliteCount = 0,
                        satellitesTruncated = false,
                        satellites = emptyList(),
                        capturedAt = now,
                        capturedAtElapsedRealtimeNanos = elapsedRealtimeNanos,
                    )
                } else {
                    current.gnss?.copy(running = false) ?: GnssSnapshot(
                        running = false,
                        timeToFirstFixMillis = null,
                        reportedSatelliteCount = 0,
                        satellitesTruncated = false,
                        satellites = emptyList(),
                        capturedAt = now,
                        capturedAtElapsedRealtimeNanos = elapsedRealtimeNanos,
                    )
                },
            )
        }
    }

    fun updateGnssTimeToFirstFix(timeToFirstFixMillis: Int) {
        _data.update { current ->
            current.copy(
                gnss = current.gnss?.copy(timeToFirstFixMillis = timeToFirstFixMillis)
                    ?: GnssSnapshot(
                        running = true,
                        timeToFirstFixMillis = timeToFirstFixMillis,
                        reportedSatelliteCount = 0,
                        satellitesTruncated = false,
                        satellites = emptyList(),
                        capturedAt = System.currentTimeMillis(),
                        capturedAtElapsedRealtimeNanos =
                            android.os.SystemClock.elapsedRealtimeNanos(),
                    ),
            )
        }
    }

    fun exerciseRoutesRequiringConsent(): List<String> = data.value.health
        ?.records
        ?.filter { record ->
            record.recordType == ExerciseSessionRecord::class.simpleName &&
                record.data.optJSONObject("exerciseRouteResult")
                    ?.optString("status") == "consentRequired"
        }
        ?.map(HealthRecordSnapshot::id)
        ?.filter(String::isNotEmpty)
        .orEmpty()

    fun updateExerciseRoute(recordId: String, route: ExerciseRoute) {
        consentedExerciseRoutes[recordId] = route
        _data.update { current ->
            val health = current.health ?: return@update current
            current.copy(
                health = health.copy(
                    records = health.records.map { record ->
                        if (record.id == recordId) record.withExerciseRoute(route) else record
                    },
                ),
            )
        }
    }

    fun updateNotifications(
        ownPackageName: String,
        notifications: Array<StatusBarNotification>,
    ) {
        val activeSnapshots = notifications
            .asSequence()
            .filter { it.packageName != ownPackageName }
            .map(::notificationSnapshot)
            .toList()
        _data.update { current ->
            val merged = (activeSnapshots + current.notifications)
                .distinctBy(NotificationSnapshot::key)
                .sortedByDescending(NotificationSnapshot::timestamp)
                .take(MAX_ITEMS)
            current.copy(notifications = merged)
        }
    }

    fun recordNotification(ownPackageName: String, notification: StatusBarNotification) {
        if (notification.packageName == ownPackageName) return
        val snapshot = notificationSnapshot(notification)
        _data.update { current ->
            current.copy(
                notifications = (listOf(snapshot) + current.notifications)
                    .distinctBy(NotificationSnapshot::key)
                    .sortedByDescending(NotificationSnapshot::timestamp)
                    .take(MAX_ITEMS),
            )
        }
    }

    private fun refreshSms(context: Context) {
        if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            _data.update { it.copy(smsMessages = emptyList()) }
            return
        }

        val queryArguments = Bundle().apply {
            putString(
                ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
                "${Telephony.Sms.DATE} DESC",
            )
            putInt(ContentResolver.QUERY_ARG_LIMIT, MAX_ITEMS)
        }
        val messages = buildList {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(
                    Telephony.Sms._ID,
                    Telephony.Sms.ADDRESS,
                    Telephony.Sms.BODY,
                    Telephony.Sms.DATE,
                    Telephony.Sms.TYPE,
                ),
                queryArguments,
                null,
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(Telephony.Sms._ID)
                val addressColumn = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val bodyColumn = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val dateColumn = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
                val typeColumn = cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE)
                while (size < MAX_ITEMS && cursor.moveToNext()) {
                    add(
                        SmsSnapshot(
                            id = cursor.getLong(idColumn),
                            address = cursor.getString(addressColumn).orEmpty(),
                            body = cursor.getString(bodyColumn).orEmpty(),
                            timestamp = cursor.getLong(dateColumn),
                            type = cursor.getInt(typeColumn),
                        ),
                    )
                }
            }
        }
        _data.update { it.copy(smsMessages = messages) }
    }

    private suspend fun refreshHealth(context: Context) {
        if (!isHealthAvailable(context)) {
            _data.update { it.copy(health = null) }
            return
        }

        runCatching {
            val client = HealthConnectClient.getOrCreate(context)
            val grantedPermissions = client.permissionController.getGrantedPermissions()
            val recordPermissions = HealthRecordCollector.requestedPermissions(client)
            if (grantedPermissions.intersect(recordPermissions).isEmpty()) {
                _data.update { it.copy(health = null) }
                return
            }
            val end = Instant.now()
            val start = LocalDate.now()
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant()
            val timeRange = TimeRangeFilter.between(start, end)
            val metrics = buildSet<AggregateMetric<*>> {
                if (hasPermission<StepsRecord>(grantedPermissions)) add(StepsRecord.COUNT_TOTAL)
                if (hasPermission<DistanceRecord>(grantedPermissions)) {
                    add(DistanceRecord.DISTANCE_TOTAL)
                }
                if (hasPermission<ActiveCaloriesBurnedRecord>(grantedPermissions)) {
                    add(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)
                }
                if (hasPermission<TotalCaloriesBurnedRecord>(grantedPermissions)) {
                    add(TotalCaloriesBurnedRecord.ENERGY_TOTAL)
                }
                if (hasPermission<ElevationGainedRecord>(grantedPermissions)) {
                    add(ElevationGainedRecord.ELEVATION_GAINED_TOTAL)
                }
                if (hasPermission<FloorsClimbedRecord>(grantedPermissions)) {
                    add(FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL)
                }
                if (hasPermission<ExerciseSessionRecord>(grantedPermissions)) {
                    add(ExerciseSessionRecord.EXERCISE_DURATION_TOTAL)
                }
                if (hasPermission<SleepSessionRecord>(grantedPermissions)) {
                    add(SleepSessionRecord.SLEEP_DURATION_TOTAL)
                }
                if (hasPermission<HeartRateRecord>(grantedPermissions)) {
                    add(HeartRateRecord.BPM_AVG)
                    add(HeartRateRecord.BPM_MIN)
                    add(HeartRateRecord.BPM_MAX)
                }
                if (hasPermission<RestingHeartRateRecord>(grantedPermissions)) {
                    add(RestingHeartRateRecord.BPM_AVG)
                }
            }
            val aggregationResult = metrics.takeIf { it.isNotEmpty() }?.let {
                runCatching {
                    client.aggregate(AggregateRequest(metrics = it, timeRangeFilter = timeRange))
                }
            }
            val aggregation = aggregationResult?.getOrNull()
            val exerciseSessionsResult =
                client.countExerciseSessions(grantedPermissions, timeRange)
            val weightResult = client.readLatestRecord<WeightRecord>(grantedPermissions, end)
            val bodyFatResult = client.readLatestRecord<BodyFatRecord>(grantedPermissions, end)
            val oxygenSaturationResult =
                client.readLatestRecord<OxygenSaturationRecord>(grantedPermissions, end)
            val recordCollection = HealthRecordCollector.collect(client, grantedPermissions, end)
            val previousHealth = _data.value.health
            val failedRecordTypes = buildSet {
                addAll(recordCollection.failedRecordTypes)
                if (aggregationResult?.isFailure == true) {
                    if (hasPermission<StepsRecord>(grantedPermissions)) add("StepsRecord")
                    if (hasPermission<DistanceRecord>(grantedPermissions)) add("DistanceRecord")
                    if (hasPermission<ActiveCaloriesBurnedRecord>(grantedPermissions)) {
                        add("ActiveCaloriesBurnedRecord")
                    }
                    if (hasPermission<TotalCaloriesBurnedRecord>(grantedPermissions)) {
                        add("TotalCaloriesBurnedRecord")
                    }
                    if (hasPermission<ElevationGainedRecord>(grantedPermissions)) {
                        add("ElevationGainedRecord")
                    }
                    if (hasPermission<FloorsClimbedRecord>(grantedPermissions)) {
                        add("FloorsClimbedRecord")
                    }
                    if (hasPermission<ExerciseSessionRecord>(grantedPermissions)) {
                        add("ExerciseSessionRecord")
                    }
                    if (hasPermission<SleepSessionRecord>(grantedPermissions)) {
                        add("SleepSessionRecord")
                    }
                    if (hasPermission<HeartRateRecord>(grantedPermissions)) {
                        add("HeartRateRecord")
                    }
                    if (hasPermission<RestingHeartRateRecord>(grantedPermissions)) {
                        add("RestingHeartRateRecord")
                    }
                }
                if (exerciseSessionsResult?.isFailure == true) add("ExerciseSessionRecord")
                if (weightResult?.isFailure == true) add("WeightRecord")
                if (bodyFatResult?.isFailure == true) add("BodyFatRecord")
                if (oxygenSaturationResult?.isFailure == true) add("OxygenSaturationRecord")
            }.sorted()
            val records = (
                recordCollection.records.map { record ->
                    consentedExerciseRoutes[record.id]
                        ?.let { route -> record.withExerciseRoute(route) }
                        ?: record
                } + previousHealth
                    ?.records
                    .orEmpty()
                    .filter { it.recordType in recordCollection.failedRecordTypes }
                )
                .distinctBy { "${it.recordType}:${it.id}" }
                .sortedByDescending(HealthRecordSnapshot::startTime)
            val medicalResources = (
                recordCollection.medicalResources + previousHealth
                    ?.medicalResources
                    .orEmpty()
                    .filter {
                        it.medicalResourceType in recordCollection.failedMedicalResourceTypes
                    }
                )
                .distinctBy {
                    "${it.medicalResourceType}:${it.dataSourceId}:${it.fhirResourceType}:${it.fhirResourceId}"
                }
            HealthSnapshot(
                steps = when {
                    !hasPermission<StepsRecord>(grantedPermissions) -> null
                    aggregationResult?.isFailure == true -> previousHealth?.steps
                    else -> aggregation?.get(StepsRecord.COUNT_TOTAL) ?: 0
                },
                distanceKilometers = when {
                    !hasPermission<DistanceRecord>(grantedPermissions) -> null
                    aggregationResult?.isFailure == true -> previousHealth?.distanceKilometers
                    else -> aggregation?.get(DistanceRecord.DISTANCE_TOTAL)?.inKilometers ?: 0.0
                },
                activeCalories = when {
                    !hasPermission<ActiveCaloriesBurnedRecord>(grantedPermissions) -> null
                    aggregationResult?.isFailure == true -> previousHealth?.activeCalories
                    else -> aggregation
                        ?.get(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)
                        ?.inKilocalories ?: 0.0
                },
                exerciseSessions = when {
                    !hasPermission<ExerciseSessionRecord>(grantedPermissions) -> null
                    exerciseSessionsResult?.isFailure == true -> previousHealth?.exerciseSessions
                    else -> exerciseSessionsResult?.getOrNull() ?: 0
                },
                totalCalories = if (!hasPermission<TotalCaloriesBurnedRecord>(grantedPermissions)) {
                    null
                } else if (aggregationResult?.isFailure == true) {
                    previousHealth?.totalCalories
                } else {
                    aggregation?.get(TotalCaloriesBurnedRecord.ENERGY_TOTAL)?.inKilocalories ?: 0.0
                },
                elevationGainedMeters = if (!hasPermission<ElevationGainedRecord>(grantedPermissions)) {
                    null
                } else if (aggregationResult?.isFailure == true) {
                    previousHealth?.elevationGainedMeters
                } else {
                    aggregation?.get(ElevationGainedRecord.ELEVATION_GAINED_TOTAL)?.inMeters ?: 0.0
                },
                floorsClimbed = if (!hasPermission<FloorsClimbedRecord>(grantedPermissions)) {
                    null
                } else if (aggregationResult?.isFailure == true) {
                    previousHealth?.floorsClimbed
                } else {
                    aggregation?.get(FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL) ?: 0.0
                },
                exerciseMinutes = if (!hasPermission<ExerciseSessionRecord>(grantedPermissions)) {
                    null
                } else if (aggregationResult?.isFailure == true) {
                    previousHealth?.exerciseMinutes
                } else {
                    aggregation?.get(ExerciseSessionRecord.EXERCISE_DURATION_TOTAL)?.toMinutes()
                        ?: 0
                },
                sleepMinutes = if (!hasPermission<SleepSessionRecord>(grantedPermissions)) {
                    null
                } else if (aggregationResult?.isFailure == true) {
                    previousHealth?.sleepMinutes
                } else {
                    aggregation?.get(SleepSessionRecord.SLEEP_DURATION_TOTAL)?.toMinutes() ?: 0
                },
                averageHeartRateBpm = if (!hasPermission<HeartRateRecord>(grantedPermissions)) {
                    null
                } else if (aggregationResult?.isFailure == true) {
                    previousHealth?.averageHeartRateBpm
                } else {
                    aggregation?.get(HeartRateRecord.BPM_AVG)
                },
                minimumHeartRateBpm = if (!hasPermission<HeartRateRecord>(grantedPermissions)) {
                    null
                } else if (aggregationResult?.isFailure == true) {
                    previousHealth?.minimumHeartRateBpm
                } else {
                    aggregation?.get(HeartRateRecord.BPM_MIN)
                },
                maximumHeartRateBpm = if (!hasPermission<HeartRateRecord>(grantedPermissions)) {
                    null
                } else if (aggregationResult?.isFailure == true) {
                    previousHealth?.maximumHeartRateBpm
                } else {
                    aggregation?.get(HeartRateRecord.BPM_MAX)
                },
                restingHeartRateBpm = if (
                    !hasPermission<RestingHeartRateRecord>(grantedPermissions)
                ) {
                    null
                } else if (aggregationResult?.isFailure == true) {
                    previousHealth?.restingHeartRateBpm
                } else {
                    aggregation?.get(RestingHeartRateRecord.BPM_AVG)
                },
                weightKilograms = if (weightResult?.isFailure == true) {
                    previousHealth?.weightKilograms
                } else {
                    weightResult?.getOrNull()?.weight?.inKilograms
                },
                bodyFatPercentage = if (bodyFatResult?.isFailure == true) {
                    previousHealth?.bodyFatPercentage
                } else {
                    bodyFatResult?.getOrNull()?.percentage?.value
                },
                oxygenSaturationPercentage = if (oxygenSaturationResult?.isFailure == true) {
                    previousHealth?.oxygenSaturationPercentage
                } else {
                    oxygenSaturationResult?.getOrNull()?.percentage?.value
                },
                records = records,
                medicalResources = medicalResources,
                supportedRecordTypes = recordCollection.supportedRecordTypes,
                grantedRecordTypes = recordCollection.grantedRecordTypes,
                supportedMedicalResourceTypes = recordCollection.supportedMedicalResourceTypes,
                grantedMedicalResourceTypes = recordCollection.grantedMedicalResourceTypes,
                failedRecordTypes = failedRecordTypes,
                failedMedicalResourceTypes = recordCollection.failedMedicalResourceTypes,
                collectedAt = System.currentTimeMillis(),
            )
        }.onSuccess { health ->
            _data.update { it.copy(health = health.withConsentedExerciseRoutes()) }
        }
    }

    private inline fun <reified T : Record> hasPermission(grantedPermissions: Set<String>): Boolean =
        HealthPermission.getReadPermission<T>() in grantedPermissions

    private suspend fun HealthConnectClient.countExerciseSessions(
        grantedPermissions: Set<String>,
        timeRange: TimeRangeFilter,
    ): Result<Int>? {
        if (!hasPermission<ExerciseSessionRecord>(grantedPermissions)) return null

        return runCatching {
            var count = 0
            var pageToken: String? = null
            do {
                val response = readRecords(
                    ReadRecordsRequest(
                        recordType = ExerciseSessionRecord::class,
                        timeRangeFilter = timeRange,
                        pageToken = pageToken,
                    ),
                )
                count += response.records.size
                pageToken = response.pageToken
            } while (pageToken != null)
            count
        }
    }

    private suspend inline fun <reified T : Record> HealthConnectClient.readLatestRecord(
        grantedPermissions: Set<String>,
        end: Instant,
    ): Result<T?>? {
        if (!hasPermission<T>(grantedPermissions)) return null

        return runCatching {
            val timeRange = if (
                HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY in grantedPermissions
            ) {
                TimeRangeFilter.before(end)
            } else {
                TimeRangeFilter.between(end.minus(LATEST_HEALTH_LOOKBACK), end)
            }
            readRecords(
                ReadRecordsRequest(
                    recordType = T::class,
                    timeRangeFilter = timeRange,
                    ascendingOrder = false,
                    pageSize = 1,
                ),
            ).records.firstOrNull()
        }
    }

    private fun HealthRecordSnapshot.withExerciseRoute(route: ExerciseRoute): HealthRecordSnapshot =
        copy(
            data = org.json.JSONObject(data.toString()).apply {
                put(
                    "exerciseRouteResult",
                    org.json.JSONObject().apply {
                        put("status", "data")
                        put("route", HealthRecordCollector.exerciseRouteData(route))
                    },
                )
            },
        )

    private fun HealthSnapshot.withConsentedExerciseRoutes(): HealthSnapshot = copy(
        records = records.map { record ->
            consentedExerciseRoutes[record.id]
                ?.let { route -> record.withExerciseRoute(route) }
                ?: record
        },
    )

    private fun notificationSnapshot(notification: StatusBarNotification): NotificationSnapshot {
        val extras = notification.notification.extras
        return NotificationSnapshot(
            key = notification.key,
            packageName = notification.packageName,
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
            text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty(),
            timestamp = notification.postTime,
        )
    }
}
