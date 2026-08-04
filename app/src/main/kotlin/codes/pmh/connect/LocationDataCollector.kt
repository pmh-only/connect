package codes.pmh.connect

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.location.GnssStatus
import android.location.Location
import android.location.LocationManager
import android.location.altitude.AltitudeConverter
import android.os.Build
import android.os.SystemClock
import androidx.annotation.RequiresApi
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

data class LocationSnapshot(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float?,
    val altitudeMeters: Double?,
    val speedMetersPerSecond: Float?,
    val provider: String,
    val timestamp: Long,
    val verticalAccuracyMeters: Float? = null,
    val speedAccuracyMetersPerSecond: Float? = null,
    val bearingDegrees: Float? = null,
    val bearingAccuracyDegrees: Float? = null,
    val mslAltitudeMeters: Double? = null,
    val mslAltitudeAccuracyMeters: Float? = null,
    val elapsedRealtimeNanos: Long = 0,
    val elapsedRealtimeUncertaintyNanos: Double? = null,
    val ageAtReceiptMillis: Long = 0,
    val isMock: Boolean = false,
    val isComplete: Boolean? = null,
    val extras: Map<String, String> = emptyMap(),
    val address: LocationAddressSnapshot? = null,
)

data class LocationAddressSnapshot(
    val sourceLocationElapsedRealtimeNanos: Long,
    val sourceProvider: String,
    val sourceLatitude: Double,
    val sourceLongitude: Double,
    val featureName: String?,
    val premises: String?,
    val subThoroughfare: String?,
    val thoroughfare: String?,
    val subLocality: String?,
    val locality: String?,
    val subAdminArea: String?,
    val adminArea: String?,
    val postalCode: String?,
    val countryCode: String?,
    val countryName: String?,
    val phone: String?,
    val url: String?,
    val latitude: Double?,
    val longitude: Double?,
    val localeLanguageTag: String?,
    val addressLines: List<String>,
    val resolvedAt: Long,
)

data class LocationProviderSnapshot(
    val name: String,
    val enabled: Boolean,
    val propertiesKnown: Boolean,
    val accuracy: Int?,
    val powerUsage: Int?,
    val hasMonetaryCost: Boolean?,
    val requiresCell: Boolean?,
    val requiresNetwork: Boolean?,
    val requiresSatellite: Boolean?,
    val supportsAltitude: Boolean?,
    val supportsBearing: Boolean?,
    val supportsSpeed: Boolean?,
    val legacyStatus: Int?,
)

data class LocationStatusSnapshot(
    val locationEnabled: Boolean,
    val reportedProviderCount: Int,
    val providersTruncated: Boolean,
    val providers: List<LocationProviderSnapshot>,
    val gnssHardwareYear: Int?,
    val gnssHardwareModelName: String?,
    val timestamp: Long,
)

data class GnssSatelliteSnapshot(
    val constellationType: Int,
    val svid: Int,
    val cn0DbHz: Float,
    val elevationDegrees: Float,
    val azimuthDegrees: Float,
    val hasEphemerisData: Boolean,
    val hasAlmanacData: Boolean,
    val usedInFix: Boolean,
    val carrierFrequencyHz: Float?,
    val basebandCn0DbHz: Float?,
    val codeType: String?,
    val elapsedRealtimeNanos: Long?,
    val elapsedRealtimeUncertaintyNanos: Double?,
)

data class GnssSnapshot(
    val running: Boolean,
    val timeToFirstFixMillis: Int?,
    val reportedSatelliteCount: Int,
    val satellitesTruncated: Boolean,
    val satellites: List<GnssSatelliteSnapshot>,
    val capturedAt: Long,
    val capturedAtElapsedRealtimeNanos: Long,
)

object LocationDataCollector {
    private const val MAX_EXTRAS = 50
    private const val MAX_EXTRA_VALUE_LENGTH = 1_024
    private const val MAX_ADDRESS_STRING_LENGTH = 512
    private const val MAX_ADDRESS_LINES = 5
    private const val MAX_SATELLITES = 128
    private const val MAX_PROVIDERS = 64
    private const val NANOS_PER_MILLISECOND = 1_000_000L

    fun fromLocation(location: Location): LocationSnapshot? {
        val latitude = location.latitude.takeIf { it.isFinite() && it in -90.0..90.0 }
            ?: return null
        val longitude = location.longitude.takeIf { it.isFinite() && it in -180.0..180.0 }
            ?: return null
        val receiptElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        val locationElapsedRealtimeNanos = location.elapsedRealtimeNanos
            .takeIf { it in 0..receiptElapsedRealtimeNanos }
            ?: return null
        val ageNanos = if (receiptElapsedRealtimeNanos >= locationElapsedRealtimeNanos) {
            receiptElapsedRealtimeNanos - locationElapsedRealtimeNanos
        } else {
            0
        }

        return LocationSnapshot(
            latitude = latitude,
            longitude = longitude,
            accuracyMeters = location
                .availableFloat(Location::hasAccuracy, Location::getAccuracy)
                ?.takeIf { it >= 0 },
            altitudeMeters = location.availableDouble(Location::hasAltitude, Location::getAltitude),
            speedMetersPerSecond = location
                .availableFloat(Location::hasSpeed, Location::getSpeed)
                ?.takeIf { it >= 0 },
            provider = location.provider.orEmpty(),
            timestamp = location.time,
            verticalAccuracyMeters = location
                .availableFloat(
                    Location::hasVerticalAccuracy,
                    Location::getVerticalAccuracyMeters,
                )
                ?.takeIf { it >= 0 },
            speedAccuracyMetersPerSecond = location
                .availableFloat(
                    Location::hasSpeedAccuracy,
                    Location::getSpeedAccuracyMetersPerSecond,
                )
                ?.takeIf { it >= 0 },
            bearingDegrees = location
                .availableFloat(Location::hasBearing, Location::getBearing)
                ?.takeIf { it >= 0f && it < 360f },
            bearingAccuracyDegrees = location
                .availableFloat(
                    Location::hasBearingAccuracy,
                    Location::getBearingAccuracyDegrees,
                )
                ?.takeIf { it >= 0 },
            mslAltitudeMeters = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                location.availableDouble(Location::hasMslAltitude, Location::getMslAltitudeMeters)
            } else {
                null
            },
            mslAltitudeAccuracyMeters =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    location.availableFloat(
                        Location::hasMslAltitudeAccuracy,
                        Location::getMslAltitudeAccuracyMeters,
                    )?.takeIf { it >= 0 }
                } else {
                    null
                },
            elapsedRealtimeNanos = locationElapsedRealtimeNanos,
            elapsedRealtimeUncertaintyNanos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                location.availableDouble(
                    Location::hasElapsedRealtimeUncertaintyNanos,
                    Location::getElapsedRealtimeUncertaintyNanos,
                )?.takeIf { it >= 0 }
            } else {
                null
            },
            ageAtReceiptMillis = ageNanos / NANOS_PER_MILLISECOND,
            isMock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                location.isMock
            } else {
                @Suppress("DEPRECATION")
                location.isFromMockProvider
            },
            isComplete = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                location.isComplete
            } else {
                null
            },
            extras = location.boundedExtras(),
        )
    }

    fun shouldReplace(current: LocationSnapshot?, candidate: LocationSnapshot): Boolean {
        if (current == null) return true
        if (candidate.elapsedRealtimeNanos != current.elapsedRealtimeNanos) {
            return candidate.elapsedRealtimeNanos > current.elapsedRealtimeNanos
        }
        if (current.mslAltitudeMeters == null && candidate.mslAltitudeMeters != null) return true

        val candidateAccuracy = candidate.accuracyMeters
        val currentAccuracy = current.accuracyMeters
        return when {
            candidateAccuracy == null -> false
            currentAccuracy == null -> true
            else -> candidateAccuracy < currentAccuracy
        }
    }

    fun canReuseAddress(
        address: LocationAddressSnapshot,
        candidate: LocationSnapshot,
        maximumDistanceMeters: Float,
    ): Boolean {
        val result = FloatArray(1)
        Location.distanceBetween(
            address.sourceLatitude,
            address.sourceLongitude,
            candidate.latitude,
            candidate.longitude,
            result,
        )
        return result[0].isFinite() && result[0] <= maximumDistanceMeters
    }

    suspend fun addMslAltitude(context: Context, location: Location): Location? {
        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            !location.hasAltitude() || location.hasMslAltitude()
        ) {
            return null
        }
        val enriched = Location(location)
        return withContext(Dispatchers.IO) {
            attempt {
                AltitudeConverter().addMslAltitudeToLocation(context, enriched)
                enriched.takeIf(Location::hasMslAltitude)
            }
        }
    }

    fun providers(
        manager: LocationManager,
        legacyStatuses: Map<String, Int> = emptyMap(),
    ): LocationStatusSnapshot {
        val timestamp = System.currentTimeMillis()
        val providerNames = attempt { manager.allProviders.toList() }
            .orEmpty()
            .distinct()
            .sorted()
        val fallbackLocationEnabled = providerNames.any { name ->
            name != LocationManager.PASSIVE_PROVIDER &&
                (attempt { manager.isProviderEnabled(name) } ?: false)
        }
        val providerSnapshots = providerNames.take(MAX_PROVIDERS).map { name ->
            val enabled = attempt { manager.isProviderEnabled(name) } ?: false
            val details = providerDetails(manager, name)
            LocationProviderSnapshot(
                name = name,
                enabled = enabled,
                propertiesKnown = details != null,
                accuracy = details?.accuracy,
                powerUsage = details?.powerUsage,
                hasMonetaryCost = details?.hasMonetaryCost,
                requiresCell = details?.requiresCell,
                requiresNetwork = details?.requiresNetwork,
                requiresSatellite = details?.requiresSatellite,
                supportsAltitude = details?.supportsAltitude,
                supportsBearing = details?.supportsBearing,
                supportsSpeed = details?.supportsSpeed,
                legacyStatus = attempt { legacyStatuses[name] },
            )
        }
        return LocationStatusSnapshot(
            locationEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                attempt { manager.isLocationEnabled } ?: fallbackLocationEnabled
            } else {
                fallbackLocationEnabled
            },
            reportedProviderCount = providerNames.size,
            providersTruncated = providerNames.size > MAX_PROVIDERS,
            providers = providerSnapshots,
            gnssHardwareYear = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                attempt { manager.gnssYearOfHardware }.takeIf { it != null && it > 0 }
            } else {
                null
            },
            gnssHardwareModelName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                attempt { manager.gnssHardwareModelName }
                    ?.take(MAX_ADDRESS_STRING_LENGTH)
                    ?.takeIf(String::isNotEmpty)
            } else {
                null
            },
            timestamp = timestamp,
        )
    }

    fun fromGnssStatus(
        status: GnssStatus,
        running: Boolean,
        timeToFirstFixMillis: Int?,
    ): GnssSnapshot {
        val capturedAt = System.currentTimeMillis()
        val capturedAtElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        val reportedSatelliteCount = attempt { status.satelliteCount } ?: 0
        val copiedSatellites = buildList {
            for (index in 0 until reportedSatelliteCount) {
                copySatellite(status, index)?.let { add(IndexedSatellite(index, it)) }
            }
        }
        val satellites = copiedSatellites
            .sortedWith(
                compareByDescending<IndexedSatellite> { it.snapshot.usedInFix }
                    .thenByDescending { it.snapshot.cn0DbHz }
                    .thenBy(IndexedSatellite::index),
            )
            .take(MAX_SATELLITES)
            .map(IndexedSatellite::snapshot)

        return GnssSnapshot(
            running = running,
            timeToFirstFixMillis = timeToFirstFixMillis,
            reportedSatelliteCount = reportedSatelliteCount,
            satellitesTruncated = satellites.size < reportedSatelliteCount,
            satellites = satellites,
            capturedAt = capturedAt,
            capturedAtElapsedRealtimeNanos = capturedAtElapsedRealtimeNanos,
        )
    }

    suspend fun reverseGeocode(
        context: Context,
        location: LocationSnapshot,
    ): LocationAddressSnapshot? {
        if (
            !location.latitude.isFinite() || location.latitude !in -90.0..90.0 ||
            !location.longitude.isFinite() || location.longitude !in -180.0..180.0
        ) {
            return null
        }
        if (!(attempt { Geocoder.isPresent() } ?: false)) return null
        val geocoder = attempt { Geocoder(context) } ?: return null

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            reverseGeocodeAsync(geocoder, location)
        } else {
            reverseGeocodeBlocking(geocoder, location)
        }
    }

    @Suppress("DEPRECATION")
    private fun providerDetails(manager: LocationManager, name: String): ProviderDetails? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val properties = attempt { manager.getProviderProperties(name) } ?: return null
            return ProviderDetails(
                accuracy = attempt { properties.accuracy },
                powerUsage = attempt { properties.powerUsage },
                hasMonetaryCost = attempt { properties.hasMonetaryCost() },
                requiresCell = attempt { properties.hasCellRequirement() },
                requiresNetwork = attempt { properties.hasNetworkRequirement() },
                requiresSatellite = attempt { properties.hasSatelliteRequirement() },
                supportsAltitude = attempt { properties.hasAltitudeSupport() },
                supportsBearing = attempt { properties.hasBearingSupport() },
                supportsSpeed = attempt { properties.hasSpeedSupport() },
            )
        }

        val provider = attempt { manager.getProvider(name) } ?: return null
        return ProviderDetails(
            accuracy = attempt { provider.accuracy },
            powerUsage = attempt { provider.powerRequirement },
            hasMonetaryCost = attempt { provider.hasMonetaryCost() },
            requiresCell = attempt { provider.requiresCell() },
            requiresNetwork = attempt { provider.requiresNetwork() },
            requiresSatellite = attempt { provider.requiresSatellite() },
            supportsAltitude = attempt { provider.supportsAltitude() },
            supportsBearing = attempt { provider.supportsBearing() },
            supportsSpeed = attempt { provider.supportsSpeed() },
        )
    }

    private fun copySatellite(status: GnssStatus, index: Int): GnssSatelliteSnapshot? {
        val constellationType = attempt { status.getConstellationType(index) } ?: return null
        val svid = attempt { status.getSvid(index) } ?: return null
        val cn0DbHz = attempt { status.getCn0DbHz(index) }
            ?.takeIf(Float::isFinite) ?: return null
        val elevationDegrees = attempt { status.getElevationDegrees(index) }
            ?.takeIf(Float::isFinite) ?: return null
        val azimuthDegrees = attempt { status.getAzimuthDegrees(index) }
            ?.takeIf(Float::isFinite) ?: return null

        return GnssSatelliteSnapshot(
            constellationType = constellationType,
            svid = svid,
            cn0DbHz = cn0DbHz,
            elevationDegrees = elevationDegrees,
            azimuthDegrees = azimuthDegrees,
            hasEphemerisData = attempt { status.hasEphemerisData(index) } ?: false,
            hasAlmanacData = attempt { status.hasAlmanacData(index) } ?: false,
            usedInFix = attempt { status.usedInFix(index) } ?: false,
            carrierFrequencyHz = attempt {
                if (status.hasCarrierFrequencyHz(index)) status.getCarrierFrequencyHz(index) else null
            }?.takeIf(Float::isFinite),
            basebandCn0DbHz = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                attempt {
                    if (status.hasBasebandCn0DbHz(index)) status.getBasebandCn0DbHz(index) else null
                }?.takeIf(Float::isFinite)
            } else {
                null
            },
            codeType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
                attempt {
                    if (status.hasCodeType(index)) status.getCodeType(index) else null
                }?.take(MAX_ADDRESS_STRING_LENGTH)
            } else {
                null
            },
            elapsedRealtimeNanos = if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN
            ) {
                attempt {
                    if (status.hasElapsedRealtimeNanos(index)) {
                        status.getElapsedRealtimeNanos(index)
                    } else {
                        null
                    }
                }
            } else {
                null
            },
            elapsedRealtimeUncertaintyNanos = if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN
            ) {
                attempt {
                    if (status.hasElapsedRealtimeUncertaintyNanos(index)) {
                        status.getElapsedRealtimeUncertaintyNanos(index)
                    } else {
                        null
                    }
                }?.takeIf(Double::isFinite)
            } else {
                null
            },
        )
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun reverseGeocodeAsync(
        geocoder: Geocoder,
        location: LocationSnapshot,
    ): LocationAddressSnapshot? = suspendCancellableCoroutine { continuation ->
        val completed = AtomicBoolean(false)

        fun complete(snapshot: LocationAddressSnapshot?) {
            if (completed.compareAndSet(false, true)) continuation.resume(snapshot)
        }

        continuation.invokeOnCancellation { completed.set(true) }
        val listener = object : Geocoder.GeocodeListener {
            override fun onGeocode(addresses: MutableList<Address>) {
                val snapshot = attempt {
                    addresses.firstOrNull()?.toSnapshot(location)
                }
                complete(snapshot)
            }

            override fun onError(errorMessage: String?) {
                complete(null)
            }
        }

        try {
            geocoder.getFromLocation(location.latitude, location.longitude, 1, listener)
        } catch (_: Exception) {
            complete(null)
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun reverseGeocodeBlocking(
        geocoder: Geocoder,
        location: LocationSnapshot,
    ): LocationAddressSnapshot? = try {
        runInterruptible(Dispatchers.IO) {
            geocoder.getFromLocation(location.latitude, location.longitude, 1)
                ?.firstOrNull()
                ?.toSnapshot(location)
        }
    } catch (exception: CancellationException) {
        throw exception
    } catch (_: Exception) {
        null
    }

    private fun Address.toSnapshot(source: LocationSnapshot): LocationAddressSnapshot =
        LocationAddressSnapshot(
            sourceLocationElapsedRealtimeNanos = source.elapsedRealtimeNanos,
            sourceProvider = source.provider,
            sourceLatitude = source.latitude,
            sourceLongitude = source.longitude,
            featureName = featureName.boundedAddressString(),
            premises = premises.boundedAddressString(),
            subThoroughfare = subThoroughfare.boundedAddressString(),
            thoroughfare = thoroughfare.boundedAddressString(),
            subLocality = subLocality.boundedAddressString(),
            locality = locality.boundedAddressString(),
            subAdminArea = subAdminArea.boundedAddressString(),
            adminArea = adminArea.boundedAddressString(),
            postalCode = postalCode.boundedAddressString(),
            countryCode = countryCode.boundedAddressString(),
            countryName = countryName.boundedAddressString(),
            phone = phone.boundedAddressString(),
            url = url.boundedAddressString(),
            latitude = if (hasLatitude()) latitude.takeIf(Double::isFinite) else null,
            longitude = if (hasLongitude()) longitude.takeIf(Double::isFinite) else null,
            localeLanguageTag = locale.toLanguageTag().boundedAddressString(),
            addressLines = buildList {
                val lastIndex = maxAddressLineIndex.coerceAtMost(MAX_ADDRESS_LINES - 1)
                for (index in 0..lastIndex) {
                    getAddressLine(index)?.boundedAddressString()?.let(::add)
                }
            },
            resolvedAt = System.currentTimeMillis(),
        )

    private fun Location.availableFloat(
        available: (Location) -> Boolean,
        value: (Location) -> Float,
    ): Float? = if (available(this)) value(this).takeIf(Float::isFinite) else null

    private fun Location.availableDouble(
        available: (Location) -> Boolean,
        value: (Location) -> Double,
    ): Double? = if (available(this)) value(this).takeIf(Double::isFinite) else null

    @Suppress("DEPRECATION")
    private fun Location.boundedExtras(): Map<String, String> {
        val source = attempt { extras } ?: return emptyMap()
        val keys = attempt { source.keySet().toList().sorted() } ?: return emptyMap()
        return buildMap {
            for (key in keys) {
                if (size >= MAX_EXTRAS) break
                val text = attempt { source.get(key)?.toString() ?: "null" } ?: continue
                put(key, text.take(MAX_EXTRA_VALUE_LENGTH))
            }
        }
    }

    private fun String?.boundedAddressString(): String? = this?.take(MAX_ADDRESS_STRING_LENGTH)

    private inline fun <T> attempt(block: () -> T): T? = try {
        block()
    } catch (_: Exception) {
        null
    }

    private data class ProviderDetails(
        val accuracy: Int?,
        val powerUsage: Int?,
        val hasMonetaryCost: Boolean?,
        val requiresCell: Boolean?,
        val requiresNetwork: Boolean?,
        val requiresSatellite: Boolean?,
        val supportsAltitude: Boolean?,
        val supportsBearing: Boolean?,
        val supportsSpeed: Boolean?,
    )

    private data class IndexedSatellite(
        val index: Int,
        val snapshot: GnssSatelliteSnapshot,
    )
}
