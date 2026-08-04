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
        put("location", location?.toJSON() ?: JSONObject.NULL)
        put("locationHistory", JSONArray().apply {
            locationHistory.take(MAX_UPLOAD_ITEMS).forEach { put(it.toJSON()) }
        })
        put("locationStatus", locationStatus?.toJSON() ?: JSONObject.NULL)
        put("gnss", gnss?.toJSON() ?: JSONObject.NULL)
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
            optJSONArray("locationHistory"),
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

    private fun LocationSnapshot.toJSON(): JSONObject = JSONObject().apply {
        put("latitude", latitude)
        put("longitude", longitude)
        put("accuracyMeters", accuracyMeters ?: JSONObject.NULL)
        put("altitudeMeters", altitudeMeters ?: JSONObject.NULL)
        put("verticalAccuracyMeters", verticalAccuracyMeters ?: JSONObject.NULL)
        put("mslAltitudeMeters", mslAltitudeMeters ?: JSONObject.NULL)
        put("mslAltitudeAccuracyMeters", mslAltitudeAccuracyMeters ?: JSONObject.NULL)
        put("speedMetersPerSecond", speedMetersPerSecond ?: JSONObject.NULL)
        put(
            "speedAccuracyMetersPerSecond",
            speedAccuracyMetersPerSecond ?: JSONObject.NULL,
        )
        put("bearingDegrees", bearingDegrees ?: JSONObject.NULL)
        put("bearingAccuracyDegrees", bearingAccuracyDegrees ?: JSONObject.NULL)
        put("provider", provider)
        put("timestamp", timestamp)
        put("elapsedRealtimeNanos", elapsedRealtimeNanos)
        put(
            "elapsedRealtimeUncertaintyNanos",
            elapsedRealtimeUncertaintyNanos ?: JSONObject.NULL,
        )
        put("ageAtReceiptMillis", ageAtReceiptMillis)
        put("isMock", isMock)
        put("isComplete", isComplete ?: JSONObject.NULL)
        put("extras", JSONObject(extras))
        put("address", address?.toJSON() ?: JSONObject.NULL)
    }

    private fun LocationAddressSnapshot.toJSON(): JSONObject = JSONObject().apply {
        put("sourceLocationElapsedRealtimeNanos", sourceLocationElapsedRealtimeNanos)
        put("sourceProvider", sourceProvider)
        put("sourceLatitude", sourceLatitude)
        put("sourceLongitude", sourceLongitude)
        put("featureName", featureName ?: JSONObject.NULL)
        put("premises", premises ?: JSONObject.NULL)
        put("subThoroughfare", subThoroughfare ?: JSONObject.NULL)
        put("thoroughfare", thoroughfare ?: JSONObject.NULL)
        put("subLocality", subLocality ?: JSONObject.NULL)
        put("locality", locality ?: JSONObject.NULL)
        put("subAdminArea", subAdminArea ?: JSONObject.NULL)
        put("adminArea", adminArea ?: JSONObject.NULL)
        put("postalCode", postalCode ?: JSONObject.NULL)
        put("countryCode", countryCode ?: JSONObject.NULL)
        put("countryName", countryName ?: JSONObject.NULL)
        put("phone", phone ?: JSONObject.NULL)
        put("url", url ?: JSONObject.NULL)
        put("latitude", latitude ?: JSONObject.NULL)
        put("longitude", longitude ?: JSONObject.NULL)
        put("localeLanguageTag", localeLanguageTag ?: JSONObject.NULL)
        put("addressLines", JSONArray(addressLines))
        put("resolvedAt", resolvedAt)
    }

    private fun LocationStatusSnapshot.toJSON(): JSONObject = JSONObject().apply {
        put("locationEnabled", locationEnabled)
        put("reportedProviderCount", reportedProviderCount)
        put("providersTruncated", providersTruncated)
        put("gnssHardwareYear", gnssHardwareYear ?: JSONObject.NULL)
        put("gnssHardwareModelName", gnssHardwareModelName ?: JSONObject.NULL)
        put("timestamp", timestamp)
        put("providers", JSONArray().apply {
            providers.forEach { provider ->
                put(JSONObject().apply {
                    put("name", provider.name)
                    put("enabled", provider.enabled)
                    put("propertiesKnown", provider.propertiesKnown)
                    put("accuracy", provider.accuracy ?: JSONObject.NULL)
                    put("powerUsage", provider.powerUsage ?: JSONObject.NULL)
                    put("hasMonetaryCost", provider.hasMonetaryCost ?: JSONObject.NULL)
                    put("requiresCell", provider.requiresCell ?: JSONObject.NULL)
                    put("requiresNetwork", provider.requiresNetwork ?: JSONObject.NULL)
                    put("requiresSatellite", provider.requiresSatellite ?: JSONObject.NULL)
                    put("supportsAltitude", provider.supportsAltitude ?: JSONObject.NULL)
                    put("supportsBearing", provider.supportsBearing ?: JSONObject.NULL)
                    put("supportsSpeed", provider.supportsSpeed ?: JSONObject.NULL)
                    put("legacyStatus", provider.legacyStatus ?: JSONObject.NULL)
                })
            }
        })
    }

    private fun GnssSnapshot.toJSON(): JSONObject = JSONObject().apply {
        put("running", running)
        put("timeToFirstFixMillis", timeToFirstFixMillis ?: JSONObject.NULL)
        put("reportedSatelliteCount", reportedSatelliteCount)
        put("satellitesTruncated", satellitesTruncated)
        put("capturedAt", capturedAt)
        put("capturedAtElapsedRealtimeNanos", capturedAtElapsedRealtimeNanos)
        put("satellites", JSONArray().apply {
            satellites.forEach { satellite ->
                put(JSONObject().apply {
                    put("constellationType", satellite.constellationType)
                    put("svid", satellite.svid)
                    put("cn0DbHz", satellite.cn0DbHz)
                    put("elevationDegrees", satellite.elevationDegrees)
                    put("azimuthDegrees", satellite.azimuthDegrees)
                    put("hasEphemerisData", satellite.hasEphemerisData)
                    put("hasAlmanacData", satellite.hasAlmanacData)
                    put("usedInFix", satellite.usedInFix)
                    put("carrierFrequencyHz", satellite.carrierFrequencyHz ?: JSONObject.NULL)
                    put("basebandCn0DbHz", satellite.basebandCn0DbHz ?: JSONObject.NULL)
                    put("codeType", satellite.codeType ?: JSONObject.NULL)
                    put("elapsedRealtimeNanos", satellite.elapsedRealtimeNanos ?: JSONObject.NULL)
                    put(
                        "elapsedRealtimeUncertaintyNanos",
                        satellite.elapsedRealtimeUncertaintyNanos ?: JSONObject.NULL,
                    )
                })
            }
        })
    }
}
