package codes.pmh.connect

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Telephony
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class ConnectService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null
    private var batteryReceiver: BroadcastReceiver? = null
    private var smsObserver: ContentObserver? = null
    private var locationListener: LocationListener? = null
    private var locationProviderReceiver: BroadcastReceiver? = null
    private var gnssStatusCallback: GnssStatus.Callback? = null
    private var geocodeJob: Job? = null
    private var pendingGeocodeLocation: LocationSnapshot? = null
    private var collectionJob: Job? = null
    private var uploadJob: Job? = null
    private val legacyProviderStatuses = mutableMapOf<String, Int>()
    private var gnssRunning = false
    private var timeToFirstFixMillis: Int? = null
    private var lastGeocodeAttempt: LocationSnapshot? = null
    private val geocodeLock = Any()

    override fun onCreate() {
        super.onCreate()
        _isRunning.value = true
        createNotificationChannel()
        promoteToForeground()

        acquireWakeLock()
        registerBatteryMonitor()
        registerSmsObserver()
        registerLocationProviderMonitor()
        registerLocationUpdates()
        registerGnssStatus()
        startPeriodicCollection()
        startPeriodicUpload()
        scheduleWatchdog(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteToForeground()
        registerSmsObserver()
        registerLocationProviderMonitor()
        registerLocationUpdates()
        registerGnssStatus()
        serviceScope.launch {
            CollectedDataRepository.refresh(applicationContext)
            DataUploader.upload(applicationContext, CollectedDataRepository.data.value)
        }
        scheduleWatchdog(this)
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        scheduleWatchdog(this)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        scheduleWatchdog(this)
        batteryReceiver?.let(::unregisterReceiver)
        batteryReceiver = null
        smsObserver?.let(contentResolver::unregisterContentObserver)
        smsObserver = null
        locationListener?.let {
            getSystemService(LocationManager::class.java).removeUpdates(it)
        }
        locationListener = null
        locationProviderReceiver?.let { runCatching { unregisterReceiver(it) } }
        locationProviderReceiver = null
        gnssStatusCallback?.let {
            runCatching { getSystemService(LocationManager::class.java).unregisterGnssStatusCallback(it) }
        }
        gnssStatusCallback = null
        CollectedDataRepository.updateGnssRunning(false)
        synchronized(geocodeLock) {
            geocodeJob?.cancel()
            geocodeJob = null
            pendingGeocodeLocation = null
        }
        serviceScope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        _isRunning.value = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun promoteToForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                if (hasBackgroundLocationAccess()) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                } else {
                    0
                }
            startForeground(NOTIFICATION_ID, notification, type)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = if (hasBackgroundLocationAccess()) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            } else {
                0
            }
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:connect-service")
            .apply {
                setReferenceCounted(false)
                acquire()
            }
    }

    private fun registerBatteryMonitor() {
        if (batteryReceiver != null) return
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                CollectedDataRepository.updateBattery(intent)
            }
        }.also { receiver ->
            ContextCompat.registerReceiver(
                this,
                receiver,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                ContextCompat.RECEIVER_EXPORTED,
            )
        }
    }

    private fun registerSmsObserver() {
        if (
            smsObserver != null ||
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_SMS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        smsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                serviceScope.launch { CollectedDataRepository.refresh(applicationContext) }
            }
        }.also { observer ->
            contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer)
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerLocationUpdates() {
        if (locationListener != null || !hasBackgroundLocationAccess()) return

        val manager = getSystemService(LocationManager::class.java)
        val providers = manager.getProviders(true)
        if (providers.isEmpty()) return
        locationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                handleLocation(location)
            }

            override fun onProviderEnabled(provider: String) {
                refreshLocationStatus()
            }

            override fun onProviderDisabled(provider: String) {
                refreshLocationStatus()
            }

            @Deprecated("Provider status callbacks are not delivered on Android 10 and newer")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
                provider?.let { legacyProviderStatuses[it] = status }
                refreshLocationStatus()
            }
        }.also { listener ->
            providers.forEach { provider ->
                runCatching {
                    manager.getLastKnownLocation(provider)?.let(::handleLocation)
                    manager.requestLocationUpdates(
                        provider,
                        LOCATION_INTERVAL_MS,
                        LOCATION_MIN_DISTANCE_METERS,
                        listener,
                        Looper.getMainLooper(),
                    )
                }
            }
        }
        refreshLocationStatus()
    }

    private fun handleLocation(location: Location) {
        val accepted = CollectedDataRepository.updateLocation(location) ?: return
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            accepted.mslAltitudeMeters == null
        ) {
            val source = Location(location)
            serviceScope.launch {
                LocationDataCollector.addMslAltitude(applicationContext, source)
                    ?.let(CollectedDataRepository::updateLocation)
            }
        }
        if (!shouldReverseGeocode(accepted)) return
        scheduleReverseGeocode(accepted)
    }

    private fun shouldReverseGeocode(location: LocationSnapshot): Boolean {
        val previous = synchronized(geocodeLock) { lastGeocodeAttempt } ?: return true
        val elapsedNanos = location.elapsedRealtimeNanos - previous.elapsedRealtimeNanos
        if (elapsedNanos >= GEOCODE_INTERVAL_MS * 1_000_000L) return true

        val distance = FloatArray(1)
        Location.distanceBetween(
            previous.latitude,
            previous.longitude,
            location.latitude,
            location.longitude,
            distance,
        )
        return distance[0] >= GEOCODE_MIN_DISTANCE_METERS
    }

    private fun scheduleReverseGeocode(location: LocationSnapshot) {
        synchronized(geocodeLock) {
            val pending = pendingGeocodeLocation
            if (pending == null || LocationDataCollector.shouldReplace(pending, location)) {
                pendingGeocodeLocation = location
            }
            if (geocodeJob?.isActive == true) {
                return
            }
            geocodeJob = serviceScope.launch {
                while (true) {
                    val next = synchronized(geocodeLock) {
                        val queued = pendingGeocodeLocation
                        if (queued == null) {
                            geocodeJob = null
                            return@launch
                        }
                        pendingGeocodeLocation = null
                        lastGeocodeAttempt = queued
                        queued
                    }
                    withTimeoutOrNull(GEOCODE_TIMEOUT_MS) {
                        LocationDataCollector.reverseGeocode(applicationContext, next)
                    }?.let(CollectedDataRepository::updateLocationAddress)
                }
            }
        }
    }

    private fun registerLocationProviderMonitor() {
        if (locationProviderReceiver != null) return
        locationProviderReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                refreshLocationStatus()
                locationListener?.let {
                    runCatching { getSystemService(LocationManager::class.java).removeUpdates(it) }
                }
                locationListener = null
                registerLocationUpdates()
            }
        }.also { receiver ->
            ContextCompat.registerReceiver(
                this,
                receiver,
                IntentFilter().apply {
                    addAction(LocationManager.MODE_CHANGED_ACTION)
                    addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
                },
                ContextCompat.RECEIVER_EXPORTED,
            )
        }
        refreshLocationStatus()
    }

    private fun refreshLocationStatus() {
        val manager = getSystemService(LocationManager::class.java)
        CollectedDataRepository.updateLocationStatus(
            LocationDataCollector.providers(manager, legacyProviderStatuses),
        )
    }

    @SuppressLint("MissingPermission")
    private fun registerGnssStatus() {
        if (
            gnssStatusCallback != null || !hasBackgroundLocationAccess() ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val manager = getSystemService(LocationManager::class.java)
        val callback = object : GnssStatus.Callback() {
            override fun onStarted() {
                gnssRunning = true
                timeToFirstFixMillis = null
                CollectedDataRepository.updateGnssRunning(true)
            }

            override fun onStopped() {
                gnssRunning = false
                CollectedDataRepository.updateGnssRunning(false)
            }

            override fun onFirstFix(ttffMillis: Int) {
                timeToFirstFixMillis = ttffMillis
                CollectedDataRepository.updateGnssTimeToFirstFix(ttffMillis)
            }

            override fun onSatelliteStatusChanged(status: GnssStatus) {
                CollectedDataRepository.updateGnssStatus(
                    LocationDataCollector.fromGnssStatus(
                        status,
                        gnssRunning,
                        timeToFirstFixMillis,
                    ),
                )
            }
        }
        val registered = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                manager.registerGnssStatusCallback(mainExecutor, callback)
            } else {
                @Suppress("DEPRECATION")
                manager.registerGnssStatusCallback(callback, Handler(Looper.getMainLooper()))
            }
        }.getOrDefault(false)
        if (registered) gnssStatusCallback = callback
    }

    private fun hasBackgroundLocationAccess(): Boolean {
        val hasForegroundLocation =
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val hasBackgroundLocation = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        return hasForegroundLocation && hasBackgroundLocation
    }

    private fun startPeriodicCollection() {
        if (collectionJob != null) return
        collectionJob = serviceScope.launch {
            while (isActive) {
                CollectedDataRepository.refresh(applicationContext)
                delay(COLLECTION_INTERVAL_MS)
            }
        }
    }

    private fun startPeriodicUpload() {
        if (uploadJob != null) return
        uploadJob = serviceScope.launch {
            while (isActive) {
                delay(UPLOAD_INTERVAL_MS)
                DataUploader.upload(applicationContext, CollectedDataRepository.data.value)
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.service_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.service_channel_description)
            setShowBadge(false)
        }

        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_connect_status)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(getString(R.string.service_notification_text))
            .setContentIntent(contentIntent)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "connect_service"
        private const val NOTIFICATION_ID = 1001
        private const val WATCHDOG_REQUEST_CODE = 1002
        private const val WATCHDOG_INTERVAL_MS = 10 * 60 * 1000L
        private const val COLLECTION_INTERVAL_MS = 5 * 60 * 1000L
        private const val UPLOAD_INTERVAL_MS = 60 * 1000L
        private const val LOCATION_INTERVAL_MS = 60 * 1000L
        private const val LOCATION_MIN_DISTANCE_METERS = 25f
        private const val GEOCODE_INTERVAL_MS = 15 * 60 * 1000L
        private const val GEOCODE_TIMEOUT_MS = 30 * 1000L
        private const val GEOCODE_MIN_DISTANCE_METERS = 100f
        private const val PREFERENCES_NAME = "connect_service"
        private const val ENABLED_KEY = "enabled"

        private val _isRunning = MutableStateFlow(false)
        val isRunning = _isRunning.asStateFlow()

        fun start(context: Context, userInitiated: Boolean = false) {
            if (userInitiated) {
                preferences(context).edit(commit = true) { putBoolean(ENABLED_KEY, true) }
            }
            if (!isEnabled(context)) return
            runCatching {
                context.startForegroundService(Intent(context, ConnectService::class.java))
            }
        }

        fun scheduleWatchdog(context: Context) {
            if (!isEnabled(context)) return
            val alarmManager = context.getSystemService(AlarmManager::class.java)
            val watchdogIntent = watchdogIntent(context, PendingIntent.FLAG_UPDATE_CURRENT)
            val triggerAt = SystemClock.elapsedRealtime() + WATCHDOG_INTERVAL_MS

            val canScheduleExactly = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                alarmManager.canScheduleExactAlarms()
            if (!canScheduleExactly) {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    watchdogIntent,
                )
                return
            }

            try {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    watchdogIntent,
                )
            } catch (_: SecurityException) {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    watchdogIntent,
                )
            }
        }

        fun cancelWatchdog(context: Context) {
            val existing = PendingIntent.getBroadcast(
                context,
                WATCHDOG_REQUEST_CODE,
                Intent(context, WatchdogReceiver::class.java),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            ) ?: return
            context.getSystemService(AlarmManager::class.java).cancel(existing)
            existing.cancel()
        }

        private fun watchdogIntent(context: Context, flags: Int): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                WATCHDOG_REQUEST_CODE,
                Intent(context, WatchdogReceiver::class.java),
                flags or PendingIntent.FLAG_IMMUTABLE,
            )

        private fun isEnabled(context: Context): Boolean = preferences(context)
            .getBoolean(ENABLED_KEY, false)

        private fun preferences(context: Context) = context
            .createDeviceProtectedStorageContext()
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    }
}
