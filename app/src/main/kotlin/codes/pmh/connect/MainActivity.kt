package codes.pmh.connect

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
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.contracts.ExerciseRouteRequestContract
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

private const val STATE_EXERCISE_ROUTE_ID = "exerciseRouteId"
private const val STATE_EXERCISE_ROUTE_QUEUE = "exerciseRouteQueue"

class MainActivity : ComponentActivity() {
    private val exerciseRouteQueue = ArrayDeque<String>()
    private var currentExerciseRouteId: String? = null
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
    ) { requestExerciseRouteAccess() }
    private val exerciseRouteLauncher = registerForActivityResult(
        ExerciseRouteRequestContract(),
    ) { route ->
        if (route != null) {
            currentExerciseRouteId?.let { recordId ->
                CollectedDataRepository.updateExerciseRoute(recordId, route)
            }
        }
        currentExerciseRouteId = null
        requestNextExerciseRoute()
    }
    private val notificationAccessLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { finishAccessFlow() }

    private var accessState by mutableStateOf(AccessState())
    private var lastCrash by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentExerciseRouteId = savedInstanceState?.getString(STATE_EXERCISE_ROUTE_ID)
        savedInstanceState
            ?.getStringArrayList(STATE_EXERCISE_ROUTE_QUEUE)
            ?.let(exerciseRouteQueue::addAll)
        enableEdgeToEdge()
        lastCrash = CrashLogStore.read(this)
        if (!ConnectService.isRunning.value) {
            ConnectService.cancelWatchdog(this)
        }
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
                    lastCrash = lastCrash,
                    onEnsureRunning = { ConnectService.start(this, userInitiated = true) },
                    onGrantAccess = { requestAllAccess() },
                    onClearCrash = {
                        CrashLogStore.clear(this)
                        lastCrash = null
                    },
                    onSaveUploadConfig = { endpoint, token ->
                        UploadConfigStore.save(this, endpoint, token).fold(
                            onSuccess = {
                                ConnectService.start(this, userInitiated = true)
                                null
                            },
                            onFailure = { it.message ?: "Could not save upload settings" },
                        )
                    },
                )
            }
        }

    }

    override fun onResume() {
        super.onResume()
        updateAccessState()
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
            if (runCatching {
                    runtimePermissionLauncher.launch(missingPermissions.toTypedArray())
                }.isFailure
            ) {
                requestBackgroundLocationAccess()
            }
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
            if (runCatching {
                    backgroundLocationPermissionLauncher.launch(
                        Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                    )
                }.isFailure
            ) {
                requestBatteryOptimizationExemption()
            }
        } else {
            if (runCatching {
                    backgroundLocationSettingsLauncher.launch(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(
                            "package:$packageName".toUri(),
                        ),
                    )
                }.isFailure
            ) {
                requestBatteryOptimizationExemption()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        currentExerciseRouteId?.let { outState.putString(STATE_EXERCISE_ROUTE_ID, it) }
        outState.putStringArrayList(
            STATE_EXERCISE_ROUTE_QUEUE,
            ArrayList(exerciseRouteQueue),
        )
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryOptimizationExemption() {
        val powerManager = getSystemService(PowerManager::class.java)
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            if (runCatching {
                    batteryExemptionLauncher.launch(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(
                            "package:$packageName".toUri(),
                        ),
                    )
                }.isFailure
            ) {
                requestExactAlarmAccess()
            }
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
            if (runCatching {
                    exactAlarmLauncher.launch(
                        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).setData(
                            "package:$packageName".toUri(),
                        ),
                    )
                }.isFailure
            ) {
                requestHealthAccess()
            }
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
            val required = runCatching {
                CollectedDataRepository.requestedHealthPermissions(this@MainActivity)
            }.getOrElse {
                requestNotificationAccess()
                return@launch
            }
            val hasPermissions = runCatching {
                CollectedDataRepository.hasHealthPermissions(this@MainActivity)
            }.getOrDefault(false)
            if (hasPermissions) {
                requestExerciseRouteAccess()
            } else if (runCatching { healthPermissionLauncher.launch(required) }.isFailure) {
                requestNotificationAccess()
            }
        }
    }

    private fun requestExerciseRouteAccess() {
        lifecycleScope.launch {
            runCatching { CollectedDataRepository.refresh(this@MainActivity) }
            exerciseRouteQueue.clear()
            exerciseRouteQueue.addAll(CollectedDataRepository.exerciseRoutesRequiringConsent())
            requestNextExerciseRoute()
        }
    }

    private fun requestNextExerciseRoute() {
        val recordId = exerciseRouteQueue.removeFirstOrNull()
        if (recordId == null) {
            requestNotificationAccess()
            return
        }

        currentExerciseRouteId = recordId
        if (runCatching { exerciseRouteLauncher.launch(recordId) }.isFailure) {
            currentExerciseRouteId = null
            requestNextExerciseRoute()
        }
    }

    private fun requestNotificationAccess() {
        if (!NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)) {
            if (runCatching {
                    notificationAccessLauncher.launch(
                        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
                    )
                }.isFailure
            ) {
                finishAccessFlow()
            }
            return
        }

        finishAccessFlow()
    }

    private fun finishAccessFlow() {
        updateAccessState()
        ConnectService.start(this, userInitiated = true)
        lifecycleScope.launch {
            CollectedDataRepository.refresh(this@MainActivity)
        }
    }

    private fun updateAccessState() {
        val powerManager = getSystemService(PowerManager::class.java)
        val alarmManager = getSystemService(AlarmManager::class.java)
        val batteryExempt = runCatching {
            powerManager.isIgnoringBatteryOptimizations(packageName)
        }.getOrDefault(false)
        val exactAlarmAccess = runCatching {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                alarmManager.canScheduleExactAlarms()
        }.getOrDefault(false)
        val smsAccess = checkSelfPermission(Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED
        val notificationAccess = runCatching {
            NotificationManagerCompat
                .getEnabledListenerPackages(this)
                .contains(packageName)
        }.getOrDefault(false)
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
            runCatching { CollectedDataRepository.refresh(this@MainActivity) }
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
        primary = Lavender,
        onPrimary = InkPurple,
        primaryContainer = DeepPurple,
        onPrimaryContainer = PaleLavender,
        secondary = Lime,
        onSecondary = InkGreen,
        secondaryContainer = Moss,
        onSecondaryContainer = PaleLime,
        tertiary = Coral,
        onTertiary = InkCoral,
        tertiaryContainer = DeepCoral,
        onTertiaryContainer = PaleCoral,
        background = Ink,
        onBackground = Cloud,
        surface = Ink,
        onSurface = Cloud,
        surfaceVariant = SoftPlum,
        onSurfaceVariant = Haze,
        error = ErrorPink,
        onError = InkCoral,
        errorContainer = ErrorContainer,
        onErrorContainer = PaleCoral,
        outline = Outline,
        outlineVariant = OutlineVariant,
    )
    val typography = Typography(
        displaySmall = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Black,
            fontSize = 44.sp,
            lineHeight = 45.sp,
            letterSpacing = (-1.5).sp,
        ),
        headlineMedium = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Bold,
            fontSize = 30.sp,
            lineHeight = 34.sp,
            letterSpacing = (-0.5).sp,
        ),
        titleLarge = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp,
            lineHeight = 27.sp,
        ),
        titleMedium = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            lineHeight = 21.sp,
        ),
        bodyLarge = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontSize = 16.sp,
            lineHeight = 24.sp,
        ),
        bodyMedium = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        ),
        labelLarge = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            letterSpacing = 0.1.sp,
        ),
    )
    val shapes = Shapes(
        extraSmall = RoundedCornerShape(8.dp),
        small = RoundedCornerShape(14.dp),
        medium = RoundedCornerShape(22.dp),
        large = RoundedCornerShape(30.dp),
        extraLarge = RoundedCornerShape(48.dp),
    )

    MaterialTheme(
        colorScheme = colors,
        typography = typography,
        shapes = shapes,
        content = content,
    )
}

@Composable
private fun ConnectScreen(
    isRunning: Boolean,
    collectedData: CollectedData,
    accessState: AccessState,
    initialUploadConfig: UploadConfig,
    uploadStatus: UploadStatus,
    lastCrash: String?,
    onEnsureRunning: () -> Unit,
    onGrantAccess: () -> Unit,
    onClearCrash: () -> Unit,
    onSaveUploadConfig: (String, String) -> String?,
) {
    val allAccessReady = accessState.batteryExempt &&
        accessState.exactAlarmAccess &&
        accessState.smsAccess &&
        accessState.locationAccess &&
        accessState.notificationAccess &&
        (!accessState.healthAvailable || accessState.healthAccess)
    val accessTotal = 5 + if (accessState.healthAvailable) 1 else 0
    val accessReady = listOf(
        accessState.batteryExempt,
        accessState.exactAlarmAccess,
        accessState.smsAccess,
        accessState.notificationAccess,
        accessState.locationAccess,
    ).count { it } + if (accessState.healthAvailable && accessState.healthAccess) 1 else 0

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        ExpressiveBackdrop()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 760.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                BrandBar(isRunning)
                HeroCard(isRunning)
                ActionCard(
                    accessReady = accessReady,
                    accessTotal = accessTotal,
                    allAccessReady = allAccessReady,
                    onEnsureRunning = onEnsureRunning,
                    onGrantAccess = onGrantAccess,
                )

                if (lastCrash != null) {
                    CrashReportCard(lastCrash, onClearCrash)
                }

                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    if (maxWidth >= 680.dp) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Box(Modifier.weight(1f)) {
                                ReliabilityCard(
                                    batteryExempt = accessState.batteryExempt,
                                    exactAlarmAccess = accessState.exactAlarmAccess,
                                )
                            }
                            Box(Modifier.weight(1f)) {
                                DataAccessCard(accessState)
                            }
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            ReliabilityCard(
                                batteryExempt = accessState.batteryExempt,
                                exactAlarmAccess = accessState.exactAlarmAccess,
                            )
                            DataAccessCard(accessState)
                        }
                    }
                }

                CollectionSummaryCard(collectedData)
                UploadSettingsCard(
                    initialConfig = initialUploadConfig,
                    uploadStatus = uploadStatus,
                    onSave = onSaveUploadConfig,
                )
                Text(
                    text = stringResource(R.string.system_limitation),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun CrashReportCard(report: String, onClear: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(
            topStart = 32.dp,
            topEnd = 32.dp,
            bottomEnd = 32.dp,
            bottomStart = 10.dp,
        ),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SectionLead(
                symbol = "!",
                title = stringResource(R.string.last_crash),
                supporting = stringResource(R.string.crash_supporting),
                accent = MaterialTheme.colorScheme.error,
            )
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.42f),
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(
                    text = report.take(2_000),
                    modifier = Modifier.padding(14.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            OutlinedButton(
                onClick = onClear,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
            ) {
                Text(stringResource(R.string.clear_crash_report))
            }
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
        UploadState.SUCCESS -> Lime
        UploadState.ERROR -> ErrorPink
        UploadState.UPLOADING -> Lavender
        UploadState.NOT_CONFIGURED -> Haze
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(
            topStart = 36.dp,
            topEnd = 12.dp,
            bottomEnd = 36.dp,
            bottomStart = 36.dp,
        ),
        colors = CardDefaults.cardColors(containerColor = DeepCoral),
    ) {
        Column(
            modifier = Modifier.padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SectionLead(
                symbol = "U",
                title = stringResource(R.string.server_upload),
                supporting = stringResource(R.string.server_upload_supporting),
                accent = Coral,
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
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Coral,
                    contentColor = InkCoral,
                ),
            ) {
                Text(stringResource(R.string.save_upload_settings))
            }
            Surface(
                color = statusColor.copy(alpha = 0.14f),
                contentColor = if (validationError != null) ErrorPink else statusColor,
                shape = CircleShape,
            ) {
                Text(
                    text = validationError ?: uploadStatus.message,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun DataAccessCard(accessState: AccessState) {
    ExpressiveSectionCard(
        symbol = "D",
        title = stringResource(R.string.data_access),
        supporting = stringResource(R.string.data_access_supporting),
        accent = Lavender,
    ) {
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

@Composable
private fun CollectionSummaryCard(data: CollectedData) {
    val healthSummary = data.health?.let {
        it.steps?.let { steps ->
            pluralStringResource(
                R.plurals.health_collection_summary,
                steps.toInt(),
                steps,
                it.records.size,
                it.grantedRecordTypes.size,
                it.supportedRecordTypes.size,
            )
        } ?: pluralStringResource(
            R.plurals.health_collection_summary_without_steps,
            it.records.size,
            it.records.size,
            it.grantedRecordTypes.size,
            it.supportedRecordTypes.size,
        )
    } ?: stringResource(R.string.waiting_for_data)
    val batterySummary = data.battery?.let {
        stringResource(
            if (it.charging) R.string.battery_charging_summary else R.string.battery_summary,
            it.levelPercent,
        )
    } ?: stringResource(R.string.waiting_for_data)
    val locationSummary = data.location?.let {
        it.accuracyMeters?.let { accuracy ->
            stringResource(
                R.string.location_summary_detailed,
                it.latitude,
                it.longitude,
                it.provider,
                accuracy,
            )
        } ?: stringResource(R.string.location_summary, it.latitude, it.longitude)
    } ?: stringResource(R.string.waiting_for_data)

    ExpressiveSectionCard(
        symbol = "C",
        title = stringResource(R.string.local_collection),
        supporting = stringResource(R.string.local_collection_supporting),
        accent = Coral,
    ) {
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

@Composable
private fun DataSummaryRow(label: String, value: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = value,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.End,
            )
        }
    }
}

@Composable
private fun ReliabilityCard(
    batteryExempt: Boolean,
    exactAlarmAccess: Boolean,
) {
    ExpressiveSectionCard(
        symbol = "R",
        title = stringResource(R.string.reliability_access),
        supporting = stringResource(R.string.reliability_supporting),
        accent = Lime,
    ) {
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

@Composable
private fun PermissionStatusRow(
    label: String,
    granted: Boolean,
    unavailable: Boolean = false,
) {
    val statusColor by animateColorAsState(
        targetValue = when {
            unavailable -> Haze
            granted -> Lime
            else -> Coral
        },
        label = "permission color",
    )
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier
                    .size(30.dp)
                    .background(statusColor.copy(alpha = 0.16f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(8.dp).background(statusColor, CircleShape))
            }
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
            )
            Surface(
                color = statusColor.copy(alpha = 0.16f),
                contentColor = statusColor,
                shape = CircleShape,
            ) {
                Text(
                    text = stringResource(
                        when {
                            unavailable -> R.string.permission_unavailable
                            granted -> R.string.permission_on
                            else -> R.string.permission_needed
                        },
                    ),
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.4.sp,
                )
            }
        }
    }
}

@Composable
private fun ConnectionMark(isRunning: Boolean) {
    val signalColor by animateColorAsState(
        targetValue = if (isRunning) Lime else Coral,
        animationSpec = tween(500),
        label = "connection color",
    )
    val sweep by animateFloatAsState(
        targetValue = if (isRunning) 286f else 112f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 180f),
        label = "connection sweep",
    )
    val pulse = rememberInfiniteTransition(label = "connection pulse")
    val pulseScale by pulse.animateFloat(
        initialValue = 0.96f,
        targetValue = if (isRunning) 1.06f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_200),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "connection pulse scale",
    )

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(154.dp)) {
        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = pulseScale
                    scaleY = pulseScale
                },
        ) {
            val center = Offset(size.width / 2, size.height / 2)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(signalColor.copy(alpha = 0.22f), Color.Transparent),
                    center = center,
                    radius = size.minDimension / 2,
                ),
                radius = size.minDimension / 2,
                center = center,
            )
            drawCircle(
                color = signalColor.copy(alpha = 0.34f),
                radius = size.minDimension * 0.42f,
                center = center,
                style = Stroke(2.dp.toPx()),
            )
            drawArc(
                color = signalColor,
                startAngle = -90f,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = Offset(size.width * 0.12f, size.height * 0.12f),
                size = androidx.compose.ui.geometry.Size(size.width * 0.76f, size.height * 0.76f),
                style = Stroke(7.dp.toPx(), cap = StrokeCap.Round),
            )
        }
        Surface(
            modifier = Modifier.size(76.dp),
            shape = RoundedCornerShape(
                topStart = 28.dp,
                topEnd = 12.dp,
                bottomEnd = 28.dp,
                bottomStart = 12.dp,
            ),
            color = signalColor,
            contentColor = if (isRunning) InkGreen else InkCoral,
            shadowElevation = 5.dp,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = "C",
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.SansSerif,
                )
            }
        }
    }
}

@Composable
private fun ExpressiveBackdrop() {
    Canvas(Modifier.fillMaxSize()) {
        drawCircle(
            color = Lavender.copy(alpha = 0.08f),
            radius = size.width * 0.48f,
            center = Offset(size.width * 1.06f, size.height * 0.08f),
        )
        drawCircle(
            color = Coral.copy(alpha = 0.055f),
            radius = size.width * 0.62f,
            center = Offset(-size.width * 0.08f, size.height * 0.72f),
        )
    }
}

@Composable
private fun BrandBar(isRunning: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            modifier = Modifier.size(44.dp),
            shape = RoundedCornerShape(
                topStart = 15.dp,
                topEnd = 15.dp,
                bottomEnd = 15.dp,
                bottomStart = 5.dp,
            ),
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("C", fontWeight = FontWeight.Black, fontSize = 20.sp)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.app_name),
                color = MaterialTheme.colorScheme.onBackground,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.brand_supporting),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        StatusPill(isRunning)
    }
}

@Composable
private fun StatusPill(isRunning: Boolean) {
    val color by animateColorAsState(
        targetValue = if (isRunning) Lime else Coral,
        label = "status pill color",
    )
    val statusText = if (isRunning) {
        stringResource(R.string.service_active)
    } else {
        stringResource(R.string.service_starting)
    }
    Surface(
        color = color.copy(alpha = 0.16f),
        contentColor = color,
        shape = CircleShape,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Box(Modifier.size(7.dp).background(color, CircleShape))
            Text(
                text = statusText,
                fontSize = 11.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun HeroCard(isRunning: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(
            topStart = 48.dp,
            topEnd = 20.dp,
            bottomEnd = 48.dp,
            bottomStart = 20.dp,
        ),
        colors = CardDefaults.cardColors(containerColor = DeepPurple),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            Canvas(
                Modifier
                    .align(Alignment.TopEnd)
                    .size(180.dp),
            ) {
                drawCircle(
                    color = Lavender.copy(alpha = 0.09f),
                    radius = size.minDimension * 0.58f,
                    center = Offset(size.width * 0.82f, size.height * 0.18f),
                )
                drawCircle(
                    color = Coral.copy(alpha = 0.12f),
                    radius = size.minDimension * 0.2f,
                    center = Offset(size.width * 0.84f, size.height * 0.2f),
                )
            }
            if (maxWidth >= 560.dp) {
                Row(
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 26.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    HeroCopy(Modifier.weight(1f))
                    ConnectionMark(isRunning)
                }
            } else {
                Column(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 26.dp),
                    horizontalAlignment = Alignment.Start,
                ) {
                    HeroCopy()
                    Spacer(Modifier.height(12.dp))
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        ConnectionMark(isRunning)
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroCopy(modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Surface(
            color = Lime,
            contentColor = InkGreen,
            shape = CircleShape,
        ) {
            Text(
                text = stringResource(R.string.boot_enabled),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                fontSize = 10.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 0.7.sp,
            )
        }
        Text(
            text = stringResource(R.string.main_title),
            color = PaleLavender,
            style = MaterialTheme.typography.displaySmall,
        )
        Text(
            text = stringResource(R.string.main_description),
            color = PaleLavender.copy(alpha = 0.78f),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun ActionCard(
    accessReady: Int,
    accessTotal: Int,
    allAccessReady: Boolean,
    onEnsureRunning: () -> Unit,
    onGrantAccess: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(
            topStart = 34.dp,
            topEnd = 34.dp,
            bottomEnd = 12.dp,
            bottomStart = 34.dp,
        ),
        colors = CardDefaults.cardColors(containerColor = Moss),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                AccessMeter(accessReady, accessTotal)
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.access_check),
                        color = PaleLime,
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = stringResource(
                            R.string.access_summary,
                            accessReady,
                            accessTotal,
                        ),
                        color = PaleLime.copy(alpha = 0.72f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                if (maxWidth >= 560.dp) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ExpressivePrimaryButton(
                            text = stringResource(R.string.ensure_running),
                            onClick = onEnsureRunning,
                            modifier = Modifier.weight(1f),
                        )
                        AccessButton(
                            allAccessReady = allAccessReady,
                            onClick = onGrantAccess,
                            modifier = Modifier.weight(1f),
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        ExpressivePrimaryButton(
                            text = stringResource(R.string.ensure_running),
                            onClick = onEnsureRunning,
                        )
                        AccessButton(allAccessReady, onGrantAccess)
                    }
                }
            }
        }
    }
}

@Composable
private fun AccessMeter(ready: Int, total: Int) {
    val progress by animateFloatAsState(
        targetValue = if (total == 0) 1f else ready.toFloat() / total,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 120f),
        label = "access progress",
    )
    Box(Modifier.size(68.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            drawArc(
                color = PaleLime.copy(alpha = 0.16f),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(7.dp.toPx(), cap = StrokeCap.Round),
            )
            drawArc(
                color = Lime,
                startAngle = -90f,
                sweepAngle = progress * 360f,
                useCenter = false,
                style = Stroke(7.dp.toPx(), cap = StrokeCap.Round),
            )
        }
        Text(
            text = "$ready/$total",
            color = PaleLime,
            fontWeight = FontWeight.Black,
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun ExpressivePrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val corner by animateDpAsState(
        targetValue = if (pressed) 16.dp else 28.dp,
        animationSpec = spring(dampingRatio = 0.62f, stiffness = 420f),
        label = "primary button shape",
    )
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.98f else 1f,
        label = "primary button scale",
    )
    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(58.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        shape = RoundedCornerShape(
            topStart = corner,
            topEnd = corner,
            bottomEnd = corner,
            bottomStart = 10.dp,
        ),
        colors = ButtonDefaults.buttonColors(
            containerColor = Lavender,
            contentColor = InkPurple,
        ),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = 3.dp,
            pressedElevation = 0.dp,
        ),
        interactionSource = interactionSource,
    ) {
        Text(text, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
private fun AccessButton(
    allAccessReady: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(58.dp),
        shape = RoundedCornerShape(28.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = PaleLime),
        border = BorderStroke(1.dp, Lime.copy(alpha = 0.58f)),
    ) {
        Text(
            text = stringResource(
                if (allAccessReady) {
                    R.string.all_access_enabled
                } else {
                    R.string.grant_all_access
                },
            ),
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ExpressiveSectionCard(
    symbol: String,
    title: String,
    supporting: String,
    accent: Color,
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    val corner by animateDpAsState(
        targetValue = if (expanded) 30.dp else 46.dp,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 260f),
        label = "section shape",
    )
    Card(
        onClick = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(
            topStart = corner,
            topEnd = 14.dp,
            bottomEnd = corner,
            bottomStart = corner,
        ),
        colors = CardDefaults.cardColors(containerColor = SoftPlum),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(
                    animationSpec = spring(dampingRatio = 0.78f, stiffness = 260f),
                )
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SectionLead(
                    symbol = symbol,
                    title = title,
                    supporting = supporting,
                    accent = accent,
                    modifier = Modifier.weight(1f),
                )
                DisclosureMark(expanded, accent)
            }
            if (expanded) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun SectionLead(
    symbol: String,
    title: String,
    supporting: String,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            modifier = Modifier.size(44.dp),
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomEnd = 5.dp,
                bottomStart = 16.dp,
            ),
            color = accent.copy(alpha = 0.16f),
            contentColor = accent,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(symbol, fontWeight = FontWeight.Black, fontSize = 17.sp)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = supporting,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun DisclosureMark(expanded: Boolean, color: Color) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = 400f),
        label = "section disclosure",
    )
    Surface(
        modifier = Modifier.size(38.dp),
        color = color.copy(alpha = 0.12f),
        shape = CircleShape,
    ) {
        Canvas(
            Modifier
                .padding(11.dp)
                .graphicsLayer { rotationZ = rotation },
        ) {
            drawLine(
                color = color,
                start = Offset(size.width * 0.16f, size.height * 0.36f),
                end = Offset(size.width * 0.5f, size.height * 0.68f),
                strokeWidth = 2.5.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawLine(
                color = color,
                start = Offset(size.width * 0.5f, size.height * 0.68f),
                end = Offset(size.width * 0.84f, size.height * 0.36f),
                strokeWidth = 2.5.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF141119)
@Composable
private fun ConnectScreenPreview() {
    ConnectTheme {
        ConnectScreen(
            isRunning = true,
            collectedData = CollectedData(
                health = HealthSnapshot(
                    steps = 8_421,
                    distanceKilometers = 6.2,
                    activeCalories = 412.0,
                    exerciseSessions = 1,
                    totalCalories = 1_830.0,
                    sleepMinutes = 438,
                    averageHeartRateBpm = 72,
                    restingHeartRateBpm = 58,
                    collectedAt = 0,
                ),
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
            lastCrash = null,
            onEnsureRunning = {},
            onGrantAccess = {},
            onClearCrash = {},
            onSaveUploadConfig = { _, _ -> null },
        )
    }
}

private val Ink = Color(0xFF141119)
private val Cloud = Color(0xFFF3EDF7)
private val SoftPlum = Color(0xFF211B29)
private val Haze = Color(0xFFC9C0D0)
private val Outline = Color(0xFF958D9D)
private val OutlineVariant = Color(0xFF4A434F)

private val Lavender = Color(0xFFD6B9FF)
private val PaleLavender = Color(0xFFECDDFF)
private val DeepPurple = Color(0xFF4D287A)
private val InkPurple = Color(0xFF32105E)

private val Lime = Color(0xFFC7F36B)
private val PaleLime = Color(0xFFE3FFAA)
private val Moss = Color(0xFF344D08)
private val InkGreen = Color(0xFF203600)

private val Coral = Color(0xFFFFB59C)
private val PaleCoral = Color(0xFFFFDBCF)
private val DeepCoral = Color(0xFF73351F)
private val InkCoral = Color(0xFF581D0C)
private val ErrorPink = Color(0xFFFFB4AB)
private val ErrorContainer = Color(0xFF6A2527)
