package com.connect.app

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
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Telephony
import androidx.core.content.ContextCompat
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

class ConnectService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null
    private var batteryReceiver: BroadcastReceiver? = null
    private var smsObserver: ContentObserver? = null
    private var locationListener: LocationListener? = null
    private var collectionJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        _isRunning.value = true
        createNotificationChannel()
        promoteToForeground()

        acquireWakeLock()
        registerBatteryMonitor()
        registerSmsObserver()
        registerLocationUpdates()
        startPeriodicCollection()
        scheduleWatchdog(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteToForeground()
        registerSmsObserver()
        registerLocationUpdates()
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
                CollectedDataRepository.updateLocation(location)
            }
        }.also { listener ->
            providers.forEach { provider ->
                runCatching {
                    manager.getLastKnownLocation(provider)?.let(
                        CollectedDataRepository::updateLocation,
                    )
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
                DataUploader.upload(applicationContext, CollectedDataRepository.data.value)
                delay(COLLECTION_INTERVAL_MS)
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
        private const val LOCATION_INTERVAL_MS = 60 * 1000L
        private const val LOCATION_MIN_DISTANCE_METERS = 25f

        private val _isRunning = MutableStateFlow(false)
        val isRunning = _isRunning.asStateFlow()

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ConnectService::class.java))
        }

        fun scheduleWatchdog(context: Context) {
            val alarmManager = context.getSystemService(AlarmManager::class.java)
            val watchdogIntent = PendingIntent.getBroadcast(
                context,
                WATCHDOG_REQUEST_CODE,
                Intent(context, WatchdogReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
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
    }
}
