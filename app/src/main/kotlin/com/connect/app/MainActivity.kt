package com.connect.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.core.app.NotificationManagerCompat
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val runtimePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { requestBackgroundLocationAccess() }
    private val backgroundLocationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { requestBatteryOptimizationExemption() }
    private val backgroundLocationSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { requestBatteryOptimizationExemption() }
    private val batteryExemptionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { requestExactAlarmAccess() }
    private val exactAlarmLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { requestHealthAccess() }
    private val healthPermissionLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
    ) { requestNotificationAccess() }
    private val notificationAccessLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { finishAccessFlow() }

    private var accessState by mutableStateOf(AccessState())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ConnectService.start(this)
        val uploadConfig = UploadConfigStore.load(this)

        setContent {
            val isRunning by ConnectService.isRunning.collectAsStateWithLifecycle()
            val collectedData by CollectedDataRepository.data.collectAsStateWithLifecycle()
            val uploadStatus by DataUploader.status.collectAsStateWithLifecycle()
            ConnectTheme {
                ConnectScreen(
                    isRunning = isRunning,
                    collectedData = collectedData,
                    accessState = accessState,
                    initialUploadConfig = uploadConfig,
                    uploadStatus = uploadStatus,
                    onEnsureRunning = { ConnectService.start(this) },
                    onGrantAccess = { requestAllAccess() },
                    onSaveUploadConfig = { endpoint, token ->
                        UploadConfigStore.save(this, endpoint, token).fold(
                            onSuccess = {
                                ConnectService.start(this)
                                null
                            },
                            onFailure = { it.message ?: "Could not save upload settings" },
                        )
                    },
                )
            }
        }

        requestAllAccess()
    }

    override fun onResume() {
        super.onResume()
        updateAccessState()
        ConnectService.scheduleWatchdog(this)
    }

    private fun requestAllAccess() {
        val missingPermissions = buildList {
            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (
                checkSelfPermission(Manifest.permission.READ_SMS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.READ_SMS)
            }
            if (!hasForegroundLocationAccess()) {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
        }
        if (missingPermissions.isNotEmpty()) {
            runtimePermissionLauncher.launch(missingPermissions.toTypedArray())
            return
        }

        requestBackgroundLocationAccess()
    }

    private fun requestBackgroundLocationAccess() {
        val hasForegroundLocation = hasForegroundLocationAccess()
        if (!hasForegroundLocation || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            requestBatteryOptimizationExemption()
            return
        }
        if (
            checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            requestBatteryOptimizationExemption()
            return
        }

        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
            backgroundLocationPermissionLauncher.launch(
                Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            )
        } else {
            backgroundLocationSettingsLauncher.launch(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(
                    "package:$packageName".toUri(),
                ),
            )
        }
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryOptimizationExemption() {
        val powerManager = getSystemService(PowerManager::class.java)
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            batteryExemptionLauncher.launch(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(
                    "package:$packageName".toUri(),
                ),
            )
            return
        }

        requestExactAlarmAccess()
    }

    private fun requestExactAlarmAccess() {
        val alarmManager = getSystemService(AlarmManager::class.java)
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !alarmManager.canScheduleExactAlarms()
        ) {
            exactAlarmLauncher.launch(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).setData(
                    "package:$packageName".toUri(),
                ),
            )
            return
        }

        requestHealthAccess()
    }

    private fun requestHealthAccess() {
        if (!CollectedDataRepository.isHealthAvailable(this)) {
            requestNotificationAccess()
            return
        }

        lifecycleScope.launch {
            val required = CollectedDataRepository.requestedHealthPermissions(this@MainActivity)
            val hasPermissions = runCatching {
                CollectedDataRepository.hasHealthPermissions(this@MainActivity)
            }.getOrDefault(false)
            if (hasPermissions) {
                requestNotificationAccess()
            } else {
                healthPermissionLauncher.launch(required)
            }
        }
    }

    private fun requestNotificationAccess() {
        if (!NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)) {
            notificationAccessLauncher.launch(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
            )
            return
        }

        finishAccessFlow()
    }

    private fun finishAccessFlow() {
        updateAccessState()
        ConnectService.start(this)
        lifecycleScope.launch {
            CollectedDataRepository.refresh(this@MainActivity)
        }
    }

    private fun updateAccessState() {
        val powerManager = getSystemService(PowerManager::class.java)
        val alarmManager = getSystemService(AlarmManager::class.java)
        val batteryExempt = powerManager.isIgnoringBatteryOptimizations(packageName)
        val exactAlarmAccess = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            alarmManager.canScheduleExactAlarms()
        val smsAccess = checkSelfPermission(Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED
        val notificationAccess = NotificationManagerCompat
            .getEnabledListenerPackages(this)
            .contains(packageName)
        val healthAvailable = CollectedDataRepository.isHealthAvailable(this)
        val locationAccess = hasForegroundLocationAccess() &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
                PackageManager.PERMISSION_GRANTED)

        lifecycleScope.launch {
            val healthAccess = healthAvailable && runCatching {
                CollectedDataRepository.hasHealthPermissions(this@MainActivity)
            }.getOrDefault(false)
            accessState = AccessState(
                batteryExempt = batteryExempt,
                exactAlarmAccess = exactAlarmAccess,
                smsAccess = smsAccess,
                healthAvailable = healthAvailable,
                healthAccess = healthAccess,
                notificationAccess = notificationAccess,
                locationAccess = locationAccess,
            )
            CollectedDataRepository.refresh(this@MainActivity)
        }
    }

    private fun hasForegroundLocationAccess(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
}

private data class AccessState(
    val batteryExempt: Boolean = false,
    val exactAlarmAccess: Boolean = false,
    val smsAccess: Boolean = false,
    val healthAvailable: Boolean = false,
    val healthAccess: Boolean = false,
    val notificationAccess: Boolean = false,
    val locationAccess: Boolean = false,
)

@Composable
private fun ConnectTheme(content: @Composable () -> Unit) {
    val colors = darkColorScheme(
        primary = Teal,
        onPrimary = Navy,
        primaryContainer = DeepTeal,
        onPrimaryContainer = Mist,
        secondary = Sky,
        background = Navy,
        onBackground = Mist,
        surface = SurfaceBlue,
        onSurface = Mist,
        onSurfaceVariant = Slate,
        outline = BorderBlue,
    )
    val typography = Typography(
        displaySmall = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Bold,
            fontSize = 40.sp,
            lineHeight = 43.sp,
            letterSpacing = (-1.2).sp,
        ),
        titleMedium = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.SemiBold,
            fontSize = 17.sp,
        ),
        bodyLarge = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontSize = 16.sp,
            lineHeight = 24.sp,
        ),
        labelLarge = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
        ),
    )

    MaterialTheme(colorScheme = colors, typography = typography, content = content)
}

@Composable
private fun ConnectScreen(
    isRunning: Boolean,
    collectedData: CollectedData,
    accessState: AccessState,
    initialUploadConfig: UploadConfig,
    uploadStatus: UploadStatus,
    onEnsureRunning: () -> Unit,
    onGrantAccess: () -> Unit,
    onSaveUploadConfig: (String, String) -> String?,
) {
    val allAccessReady = accessState.batteryExempt &&
        accessState.exactAlarmAccess &&
        accessState.smsAccess &&
        accessState.locationAccess &&
        accessState.notificationAccess &&
        (!accessState.healthAvailable || accessState.healthAccess)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to Navy,
                    0.6f to Navy,
                    1f to DarkTeal,
                ),
            )
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.app_name).uppercase(),
                color = Teal,
                fontSize = 13.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 3.sp,
            )

            Spacer(Modifier.height(28.dp))
            ConnectionMark(isRunning)
            Spacer(Modifier.height(28.dp))

            Text(
                text = stringResource(R.string.main_title),
                style = MaterialTheme.typography.displaySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.main_description),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(32.dp))
            StatusCard(isRunning)
            Spacer(Modifier.height(12.dp))
            ReliabilityCard(
                batteryExempt = accessState.batteryExempt,
                exactAlarmAccess = accessState.exactAlarmAccess,
            )
            Spacer(Modifier.height(12.dp))
            DataAccessCard(accessState)
            Spacer(Modifier.height(12.dp))
            CollectionSummaryCard(collectedData)
            Spacer(Modifier.height(12.dp))
            UploadSettingsCard(
                initialConfig = initialUploadConfig,
                uploadStatus = uploadStatus,
                onSave = onSaveUploadConfig,
            )
            Spacer(Modifier.height(16.dp))

            Button(
                onClick = onEnsureRunning,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Teal,
                    contentColor = Navy,
                ),
            ) {
                Text(stringResource(R.string.ensure_running))
            }

            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = onGrantAccess,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Mist),
                border = BorderStroke(
                    width = 1.dp,
                    brush = Brush.linearGradient(listOf(BorderBlue, DeepTeal)),
                ),
            ) {
                Text(
                    stringResource(
                        if (allAccessReady) {
                            R.string.all_access_enabled
                        } else {
                            R.string.grant_all_access
                        },
                    ),
                )
            }

            Spacer(Modifier.height(20.dp))
            Text(
                text = stringResource(R.string.system_limitation),
                color = MutedSlate,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun UploadSettingsCard(
    initialConfig: UploadConfig,
    uploadStatus: UploadStatus,
    onSave: (String, String) -> String?,
) {
    var endpoint by remember { mutableStateOf(initialConfig.endpoint) }
    var token by remember { mutableStateOf(initialConfig.token) }
    var validationError by remember { mutableStateOf<String?>(null) }
    val statusColor = when (uploadStatus.state) {
        UploadState.SUCCESS -> Active
        UploadState.ERROR -> Waiting
        UploadState.UPLOADING -> Teal
        UploadState.NOT_CONFIGURED -> MutedSlate
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceBlue.copy(alpha = 0.72f)),
        border = CardDefaults.outlinedCardBorder(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 17.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.server_upload),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
            )
            OutlinedTextField(
                value = endpoint,
                onValueChange = { endpoint = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.collection_endpoint)) },
                placeholder = { Text(stringResource(R.string.collection_endpoint_example)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                singleLine = true,
            )
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.authorization_token)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
            )
            Button(
                onClick = { validationError = onSave(endpoint, token) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = DeepTeal,
                    contentColor = Mist,
                ),
            ) {
                Text(stringResource(R.string.save_upload_settings))
            }
            Text(
                text = validationError ?: uploadStatus.message,
                color = if (validationError != null) Waiting else statusColor,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
        }
    }
}

@Composable
private fun DataAccessCard(accessState: AccessState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceBlue.copy(alpha = 0.72f)),
        border = CardDefaults.outlinedCardBorder(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 17.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.data_access),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
            )
            PermissionStatusRow(
                label = stringResource(R.string.sms_access),
                granted = accessState.smsAccess,
            )
            PermissionStatusRow(
                label = if (accessState.healthAvailable) {
                    stringResource(R.string.health_connect_access)
                } else {
                    stringResource(R.string.health_connect_unavailable)
                },
                granted = accessState.healthAccess,
                unavailable = !accessState.healthAvailable,
            )
            PermissionStatusRow(
                label = stringResource(R.string.notification_access),
                granted = accessState.notificationAccess,
            )
            PermissionStatusRow(
                label = stringResource(R.string.location_access),
                granted = accessState.locationAccess,
            )
        }
    }
}

@Composable
private fun CollectionSummaryCard(data: CollectedData) {
    val healthSummary = data.health?.let {
        pluralStringResource(R.plurals.steps_summary, it.steps.toInt(), it.steps)
    } ?: stringResource(R.string.waiting_for_data)
    val batterySummary = data.battery?.let {
        stringResource(
            if (it.charging) R.string.battery_charging_summary else R.string.battery_summary,
            it.levelPercent,
        )
    } ?: stringResource(R.string.waiting_for_data)
    val locationSummary = data.location?.let {
        stringResource(R.string.location_summary, it.latitude, it.longitude)
    } ?: stringResource(R.string.waiting_for_data)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceBlue.copy(alpha = 0.72f)),
        border = CardDefaults.outlinedCardBorder(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 17.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.local_collection),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
            )
            DataSummaryRow(stringResource(R.string.health_activity), healthSummary)
            DataSummaryRow(
                stringResource(R.string.sms_messages),
                pluralStringResource(
                    R.plurals.items_recent,
                    data.smsMessages.size,
                    data.smsMessages.size,
                ),
            )
            DataSummaryRow(
                stringResource(R.string.android_notifications),
                pluralStringResource(
                    R.plurals.items_recent,
                    data.notifications.size,
                    data.notifications.size,
                ),
            )
            DataSummaryRow(stringResource(R.string.battery_status), batterySummary)
            DataSummaryRow(stringResource(R.string.location_status), locationSummary)
        }
    }
}

@Composable
private fun DataSummaryRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            color = Mist,
            fontSize = 14.sp,
        )
        Text(
            text = value,
            color = Teal,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun ReliabilityCard(
    batteryExempt: Boolean,
    exactAlarmAccess: Boolean,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceBlue.copy(alpha = 0.72f)),
        border = CardDefaults.outlinedCardBorder(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 17.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.reliability_access),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
            )
            PermissionStatusRow(
                label = stringResource(R.string.battery_unrestricted),
                granted = batteryExempt,
            )
            PermissionStatusRow(
                label = stringResource(R.string.exact_alarm_watchdog),
                granted = exactAlarmAccess,
            )
        }
    }
}

@Composable
private fun PermissionStatusRow(
    label: String,
    granted: Boolean,
    unavailable: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .size(9.dp)
                .background(
                    when {
                        unavailable -> MutedSlate
                        granted -> Active
                        else -> Waiting
                    },
                    CircleShape,
                ),
        )
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            color = Mist,
            fontSize = 14.sp,
        )
        Text(
            text = stringResource(
                when {
                    unavailable -> R.string.permission_unavailable
                    granted -> R.string.permission_on
                    else -> R.string.permission_needed
                },
            ),
            color = when {
                unavailable -> MutedSlate
                granted -> Active
                else -> Waiting
            },
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun ConnectionMark(isRunning: Boolean) {
    val signalColor = if (isRunning) Active else Waiting

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(142.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2, size.height / 2)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(signalColor.copy(alpha = 0.18f), Color.Transparent),
                    center = center,
                    radius = size.minDimension / 2,
                ),
                radius = size.minDimension / 2,
                center = center,
            )
            drawCircle(
                color = signalColor.copy(alpha = 0.22f),
                radius = size.minDimension * 0.42f,
                center = center,
                style = Stroke(1.dp.toPx()),
            )
            drawArc(
                color = signalColor,
                startAngle = -38f,
                sweepAngle = 162f,
                useCenter = false,
                topLeft = Offset(size.width * 0.12f, size.height * 0.12f),
                size = androidx.compose.ui.geometry.Size(size.width * 0.76f, size.height * 0.76f),
                style = Stroke(3.dp.toPx(), cap = StrokeCap.Round),
            )
        }
        Surface(
            modifier = Modifier
                .size(64.dp)
                .border(1.dp, signalColor.copy(alpha = 0.55f), CircleShape),
            shape = CircleShape,
            color = SurfaceBlue,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = "C",
                    color = signalColor,
                    fontSize = 25.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.SansSerif,
                )
            }
        }
    }
}

@Composable
private fun StatusCard(isRunning: Boolean) {
    val signalColor = if (isRunning) Active else Waiting
    val statusText = if (isRunning) {
        stringResource(R.string.service_active)
    } else {
        stringResource(R.string.service_starting)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceBlue.copy(alpha = 0.92f)),
        border = CardDefaults.outlinedCardBorder(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 19.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier
                    .size(11.dp)
                    .background(signalColor, CircleShape),
            )
            Text(
                text = statusText,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.boot_enabled),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
                maxLines = 1,
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF07111F)
@Composable
private fun ConnectScreenPreview() {
    ConnectTheme {
        ConnectScreen(
            isRunning = true,
            collectedData = CollectedData(
                health = HealthSnapshot(8_421, 6.2, 412.0, 1, 0),
                smsMessages = listOf(SmsSnapshot(1, "Contact", "Message", 0, 1)),
                notifications = listOf(NotificationSnapshot("1", "app", "Title", "Text", 0)),
                battery = BatterySnapshot(78, true, 31.2, 1),
            ),
            accessState = AccessState(
                batteryExempt = true,
                exactAlarmAccess = true,
                smsAccess = true,
                healthAvailable = true,
                healthAccess = true,
                notificationAccess = true,
                locationAccess = true,
            ),
            initialUploadConfig = UploadConfig(
                endpoint = "https://connect.example/api/collect",
                token = "secret",
            ),
            uploadStatus = UploadStatus(
                state = UploadState.SUCCESS,
                message = "Last upload succeeded",
            ),
            onEnsureRunning = {},
            onGrantAccess = {},
            onSaveUploadConfig = { _, _ -> null },
        )
    }
}

private val Navy = Color(0xFF07111F)
private val DarkTeal = Color(0xFF082526)
private val SurfaceBlue = Color(0xFF101D2F)
private val BorderBlue = Color(0xFF26364C)
private val DeepTeal = Color(0xFF115E59)
private val Teal = Color(0xFF5EEAD4)
private val Sky = Color(0xFF7DD3FC)
private val Active = Color(0xFF34D399)
private val Waiting = Color(0xFFFBBF24)
private val Mist = Color(0xFFF1F5F9)
private val Slate = Color(0xFFA5B4C6)
private val MutedSlate = Color(0xFF718096)
