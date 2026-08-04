package codes.pmh.connect

import android.content.Context
import android.os.Build
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

enum class UploadState {
    NOT_CONFIGURED,
    UPLOADING,
    SUCCESS,
    ERROR,
}

data class UploadStatus(
    val state: UploadState = UploadState.NOT_CONFIGURED,
    val message: String = "Upload is not configured",
    val timestamp: Long = 0,
)

object DataUploader {
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val MAX_TEXT_LENGTH = 8_192
    private const val MAX_UPLOAD_ITEMS = 100
    private const val MAX_PAYLOAD_BYTES = 3_750_000

    private val mutex = Mutex()
    private val _status = MutableStateFlow(UploadStatus())
    val status = _status.asStateFlow()

    suspend fun upload(context: Context, data: CollectedData) = mutex.withLock {
        val config = UploadConfigStore.load(context)
        if (!config.configured) {
            _status.value = UploadStatus()
            return@withLock
        }
        _status.value = UploadStatus(UploadState.UPLOADING, "Uploading snapshot")
        runCatching {
            withContext(Dispatchers.IO) {
                send(context, config, data)
            }
        }.onSuccess {
            _status.value = UploadStatus(
                UploadState.SUCCESS,
                "Last upload succeeded",
                System.currentTimeMillis(),
            )
        }.onFailure { error ->
            _status.value = UploadStatus(
                UploadState.ERROR,
                error.message ?: "Upload failed",
                System.currentTimeMillis(),
            )
        }
    }

    private fun send(context: Context, config: UploadConfig, data: CollectedData) {
        val payload = data.toJSON(context).toBoundedPayload()
        val connection = URL(config.endpoint).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(payload.size)
            connection.setRequestProperty("Authorization", "Bearer ${config.token}")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.outputStream.use { it.write(payload) }
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                val detail = connection.errorStream
                    ?.bufferedReader()
                    ?.use { it.readLine() }
                    ?.trim()
                    ?.take(512)
                    .orEmpty()
                val suffix = if (detail.isEmpty()) "" else ": $detail"
                throw IllegalStateException("Server returned HTTP $responseCode$suffix")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun CollectedData.toJSON(context: Context): JSONObject = JSONObject().apply {
        put("deviceId", UploadConfigStore.deviceID(context))
        put("deviceName", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
        put("collectedAt", System.currentTimeMillis())
        put("health", health?.let { snapshot ->
            JSONObject().apply {
                put("steps", snapshot.steps ?: JSONObject.NULL)
                put("distanceKilometers", snapshot.distanceKilometers ?: JSONObject.NULL)
                put("activeCalories", snapshot.activeCalories ?: JSONObject.NULL)
                put("exerciseSessions", snapshot.exerciseSessions ?: JSONObject.NULL)
                put("totalCalories", snapshot.totalCalories ?: JSONObject.NULL)
                put("elevationGainedMeters", snapshot.elevationGainedMeters ?: JSONObject.NULL)
                put("floorsClimbed", snapshot.floorsClimbed ?: JSONObject.NULL)
                put("exerciseMinutes", snapshot.exerciseMinutes ?: JSONObject.NULL)
                put("sleepMinutes", snapshot.sleepMinutes ?: JSONObject.NULL)
                put("averageHeartRateBpm", snapshot.averageHeartRateBpm ?: JSONObject.NULL)
                put("minimumHeartRateBpm", snapshot.minimumHeartRateBpm ?: JSONObject.NULL)
                put("maximumHeartRateBpm", snapshot.maximumHeartRateBpm ?: JSONObject.NULL)
                put("restingHeartRateBpm", snapshot.restingHeartRateBpm ?: JSONObject.NULL)
                put("weightKilograms", snapshot.weightKilograms ?: JSONObject.NULL)
                put("bodyFatPercentage", snapshot.bodyFatPercentage ?: JSONObject.NULL)
                put(
                    "oxygenSaturationPercentage",
                    snapshot.oxygenSaturationPercentage ?: JSONObject.NULL,
                )
                put("supportedRecordTypes", JSONArray(snapshot.supportedRecordTypes))
                put("grantedRecordTypes", JSONArray(snapshot.grantedRecordTypes))
                put(
                    "supportedMedicalResourceTypes",
                    JSONArray(snapshot.supportedMedicalResourceTypes),
                )
                put(
                    "grantedMedicalResourceTypes",
                    JSONArray(snapshot.grantedMedicalResourceTypes),
                )
                put("failedRecordTypes", JSONArray(snapshot.failedRecordTypes))
                put(
                    "failedMedicalResourceTypes",
                    JSONArray(snapshot.failedMedicalResourceTypes),
                )
                put("records", JSONArray().apply {
                    snapshot.records.forEach { record ->
                        put(JSONObject().apply {
                            put("id", record.id)
                            put("recordType", record.recordType)
                            put("startTime", record.startTime.toEpochMilli())
                            put("endTime", record.endTime?.toEpochMilli() ?: JSONObject.NULL)
                            put(
                                "startZoneOffsetSeconds",
                                record.startZoneOffsetSeconds ?: JSONObject.NULL,
                            )
                            put(
                                "endZoneOffsetSeconds",
                                record.endZoneOffsetSeconds ?: JSONObject.NULL,
                            )
                            put("lastModifiedTime", record.lastModifiedTime.toEpochMilli())
                            put("dataOrigin", record.dataOrigin)
                            put("recordingMethod", record.recordingMethod)
                            put("clientRecordId", record.clientRecordId ?: JSONObject.NULL)
                            put("clientRecordVersion", record.clientRecordVersion)
                            put("deviceType", record.deviceType ?: JSONObject.NULL)
                            put(
                                "deviceManufacturer",
                                record.deviceManufacturer ?: JSONObject.NULL,
                            )
                            put("deviceModel", record.deviceModel ?: JSONObject.NULL)
                            put("data", record.data)
                        })
                    }
                })
                put("medicalResources", JSONArray().apply {
                    snapshot.medicalResources.forEach { resource ->
                        put(JSONObject().apply {
                            put("medicalResourceType", resource.medicalResourceType)
                            put("dataSourceId", resource.dataSourceId)
                            put("fhirResourceType", resource.fhirResourceType)
                            put("fhirResourceId", resource.fhirResourceId)
                            put("fhirVersion", resource.fhirVersion)
                            put("fhirJson", resource.fhirJson)
                            put("fhirJsonTruncated", resource.fhirJsonTruncated)
                        })
                    }
                })
                put("collectedAt", snapshot.collectedAt)
            }
        } ?: JSONObject.NULL)
        put("smsMessages", JSONArray().apply {
            smsMessages.take(MAX_UPLOAD_ITEMS).forEach { message ->
                put(JSONObject().apply {
                    put("id", message.id)
                    put("address", message.address.take(512))
                    put("body", message.body.take(MAX_TEXT_LENGTH))
                    put("timestamp", message.timestamp)
                    put("type", message.type)
                })
            }
        })
        put("notifications", JSONArray().apply {
            notifications.take(MAX_UPLOAD_ITEMS).forEach { notification ->
                put(JSONObject().apply {
                    put("key", notification.key.take(1_024))
                    put("packageName", notification.packageName.take(512))
                    put("title", notification.title.take(2_048))
                    put("text", notification.text.take(MAX_TEXT_LENGTH))
                    put("timestamp", notification.timestamp)
                })
            }
        })
        put("battery", battery?.let { snapshot ->
            JSONObject().apply {
                put("levelPercent", snapshot.levelPercent)
                put("charging", snapshot.charging)
                put("temperatureCelsius", snapshot.temperatureCelsius)
                put("plugged", snapshot.plugged)
            }
        } ?: JSONObject.NULL)
        put("location", location?.let { snapshot ->
            JSONObject().apply {
                put("latitude", snapshot.latitude)
                put("longitude", snapshot.longitude)
                put("accuracyMeters", snapshot.accuracyMeters.toDouble())
                put("altitudeMeters", snapshot.altitudeMeters)
                put("speedMetersPerSecond", snapshot.speedMetersPerSecond.toDouble())
                put("provider", snapshot.provider)
                put("timestamp", snapshot.timestamp)
            }
        } ?: JSONObject.NULL)
    }

    private fun JSONObject.toBoundedPayload(): ByteArray {
        fun encode(): ByteArray = toString().toByteArray(Charsets.UTF_8)

        var payload = encode()
        if (payload.size <= MAX_PAYLOAD_BYTES) return payload

        put("truncatedForUpload", true)
        val health = optJSONObject("health")
        val removableArrays = listOfNotNull(
            optJSONArray("smsMessages"),
            optJSONArray("notifications"),
            health?.optJSONArray("medicalResources"),
            health?.optJSONArray("records"),
        )
        removableArrays.forEach { values ->
            while (values.length() > 0) {
                values.remove(values.length() - 1)
                payload = encode()
                if (payload.size <= MAX_PAYLOAD_BYTES) return payload
            }
        }
        throw IllegalStateException("Collection payload exceeds upload limit")
    }
}
