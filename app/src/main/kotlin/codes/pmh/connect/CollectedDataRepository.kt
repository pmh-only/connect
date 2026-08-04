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
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class CollectedData(
    val health: HealthSnapshot? = null,
    val smsMessages: List<SmsSnapshot> = emptyList(),
    val notifications: List<NotificationSnapshot> = emptyList(),
    val battery: BatterySnapshot? = null,
    val location: LocationSnapshot? = null,
)

data class HealthSnapshot(
    val steps: Long,
    val distanceKilometers: Double,
    val activeCalories: Double,
    val exerciseSessions: Int,
    val collectedAt: Long,
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

data class LocationSnapshot(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val altitudeMeters: Double,
    val speedMetersPerSecond: Float,
    val provider: String,
    val timestamp: Long,
)

object CollectedDataRepository {
    private const val MAX_ITEMS = 100

    private val activityHealthPermissions = setOf(
        HealthPermission.getReadPermission<StepsRecord>(),
        HealthPermission.getReadPermission<DistanceRecord>(),
        HealthPermission.getReadPermission<ActiveCaloriesBurnedRecord>(),
        HealthPermission.getReadPermission<ExerciseSessionRecord>(),
    )

    private val _data = MutableStateFlow(CollectedData())
    val data = _data.asStateFlow()

    fun isHealthAvailable(context: Context): Boolean = runCatching {
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
    }.getOrDefault(false)

    fun requestedHealthPermissions(context: Context): Set<String> {
        if (!isHealthAvailable(context)) return emptySet()

        val permissions = activityHealthPermissions.toMutableSet()
        val client = HealthConnectClient.getOrCreate(context)
        if (
            client.features.getFeatureStatus(
                HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND,
            ) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
        ) {
            permissions += HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND
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

    fun updateLocation(location: Location) {
        _data.update {
            it.copy(
                location = LocationSnapshot(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    accuracyMeters = location.accuracy,
                    altitudeMeters = location.altitude,
                    speedMetersPerSecond = location.speed,
                    provider = location.provider.orEmpty(),
                    timestamp = location.time,
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
        if (!isHealthAvailable(context) || !hasHealthPermissions(context)) {
            _data.update { it.copy(health = null) }
            return
        }

        runCatching {
            val client = HealthConnectClient.getOrCreate(context)
            val end = Instant.now()
            val start = LocalDate.now()
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant()
            val timeRange = TimeRangeFilter.between(start, end)
            val aggregation = client.aggregate(
                AggregateRequest(
                    metrics = setOf(
                        StepsRecord.COUNT_TOTAL,
                        DistanceRecord.DISTANCE_TOTAL,
                        ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL,
                    ),
                    timeRangeFilter = timeRange,
                ),
            )
            val exercises = client.readRecords(
                ReadRecordsRequest(
                    recordType = ExerciseSessionRecord::class,
                    timeRangeFilter = timeRange,
                ),
            )
            HealthSnapshot(
                steps = aggregation[StepsRecord.COUNT_TOTAL] ?: 0,
                distanceKilometers =
                    aggregation[DistanceRecord.DISTANCE_TOTAL]?.inKilometers ?: 0.0,
                activeCalories =
                    aggregation[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]
                        ?.inKilocalories ?: 0.0,
                exerciseSessions = exercises.records.size,
                collectedAt = System.currentTimeMillis(),
            )
        }.onSuccess { health ->
            _data.update { it.copy(health = health) }
        }
    }

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
