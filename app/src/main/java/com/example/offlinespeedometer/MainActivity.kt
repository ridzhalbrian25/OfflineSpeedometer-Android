package com.example.offlinespeedometer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.offlinespeedometer.model.NavigationTab
import com.example.offlinespeedometer.model.SpeedUnit
import com.example.offlinespeedometer.ui.components.*
import com.example.offlinespeedometer.ui.dial.DigitalSpeedDisplay
import com.example.offlinespeedometer.ui.dial.GpsMetricsDisplay
import com.example.offlinespeedometer.ui.dial.ReferenceSpeedometerDial
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF000000)
                ) {
                    GpsSpeedometerScreen()
                }
            }
        }
    }
}

@Composable
fun GpsSpeedometerScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("speedometer_prefs", Context.MODE_PRIVATE) }

    var activeTab by remember { mutableStateOf(NavigationTab.ANALOG) }
    var unit by remember {
        val saved = prefs.getString("unit", SpeedUnit.METRIC.name) ?: SpeedUnit.METRIC.name
        mutableStateOf(try { SpeedUnit.valueOf(saved) } catch (_: Exception) { SpeedUnit.METRIC })
    }

    var speedLimitKmh by remember {
        mutableFloatStateOf(prefs.getFloat("speed_limit_kmh", 0f))
    }

    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    var isTripRunning by remember { mutableStateOf(true) }
    var tripElapsedSeconds by remember { mutableLongStateOf(0L) }
    var movingSeconds by remember { mutableLongStateOf(0L) }

    var currentSpeedMps by remember { mutableFloatStateOf(0f) }
    var maxSpeedMps by remember { mutableFloatStateOf(0f) }
    var totalDistanceMeters by remember { mutableDoubleStateOf(0.0) }
    var altitudeMeters by remember { mutableDoubleStateOf(0.0) }
    var accuracyMeters by remember { mutableFloatStateOf(0f) }
    var bearingDegrees by remember { mutableFloatStateOf(0f) }
    var hasBearing by remember { mutableStateOf(false) }
    var latitude by remember { mutableDoubleStateOf(0.0) }
    var longitude by remember { mutableDoubleStateOf(0.0) }
    var gpsFixCount by remember { mutableIntStateOf(0) }

    var isHudMirror by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    var lastLocation by remember { mutableStateOf<Location?>(null) }

    // Haptic vibration feedback
    fun triggerVibration() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(
                    VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                vibrator?.let {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        it.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
                    } else {
                        it.vibrate(200)
                    }
                }
            }
        } catch (_: Exception) {}
    }

    // Permission Launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasLocationPermission = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
    }

    // Trip Timer coroutine
    LaunchedEffect(isTripRunning) {
        while (true) {
            delay(1000L)
            if (isTripRunning) {
                tripElapsedSeconds += 1L
                if (currentSpeedMps > 0.45f) {
                    movingSeconds += 1L
                }
            }
        }
    }

    // GPS Location listener
    DisposableEffect(hasLocationPermission) {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                gpsFixCount++
                val measuredMps = if (location.hasSpeed()) {
                    location.speed
                } else {
                    lastLocation?.let { prev ->
                        val dt = (location.time - prev.time) / 1000.0f
                        if (dt > 0f) prev.distanceTo(location) / dt else 0f
                    } ?: 0f
                }

                val cleanMps = if (measuredMps < 0.35f) 0f else measuredMps
                currentSpeedMps = cleanMps

                if (isTripRunning) {
                    if (cleanMps > maxSpeedMps) {
                        maxSpeedMps = cleanMps
                    }
                    lastLocation?.let { prev ->
                        val stepMeters = prev.distanceTo(location).toDouble()
                        if (stepMeters in 0.5..500.0 && cleanMps > 0.35f) {
                            totalDistanceMeters += stepMeters
                        }
                    }
                }

                lastLocation = location
                if (location.hasAltitude()) altitudeMeters = location.altitude
                if (location.hasAccuracy()) accuracyMeters = location.accuracy
                if (location.hasBearing()) {
                    bearingDegrees = location.bearing
                    hasBearing = true
                }
                latitude = location.latitude
                longitude = location.longitude
            }

            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }

        if (hasLocationPermission) {
            try {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    300L,
                    0f,
                    listener
                )
            } catch (_: SecurityException) {}
        }

        onDispose {
            locationManager.removeUpdates(listener)
        }
    }

    // Mathematical conversions
    val displaySpeed = currentSpeedMps * unit.speedMultiplier
    val maxDisplaySpeed = maxSpeedMps * unit.speedMultiplier
    val displayDistance = totalDistanceMeters * unit.distanceMultiplier
    val avgSpeedDisplay = if (movingSeconds > 3L) {
        (totalDistanceMeters / movingSeconds.toDouble() * unit.speedMultiplier).toFloat()
    } else 0f

    // GPS Signal strength (0 to 4 bars)
    val gpsSignalLevel = when {
        !hasLocationPermission || gpsFixCount == 0 -> 0
        accuracyMeters in 0.1f..5.0f -> 4
        accuracyMeters in 5.0f..12.0f -> 3
        accuracyMeters in 12.0f..30.0f -> 2
        accuracyMeters > 30.0f -> 1
        else -> 1
    }

    // Speed limit warning evaluation
    val activeSpeedLimitInUnit = if (speedLimitKmh > 0f) {
        speedLimitKmh * (unit.speedMultiplier / 3.6f)
    } else 0f
    val isOverSpeed = activeSpeedLimitInUnit > 0f && displaySpeed > activeSpeedLimitInUnit

    var lastAlertTime by remember { mutableLongStateOf(0L) }
    LaunchedEffect(isOverSpeed) {
        if (isOverSpeed) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastAlertTime > 2500L) {
                lastAlertTime = now
                triggerVibration()
            }
        }
    }

    // Smooth speed animation
    val animatedSpeed by animateFloatAsState(
        targetValue = displaySpeed,
        animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
        label = "animatedSpeed"
    )

    // Pulse animation for warning
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 500),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = if (isHudMirror) -1f else 1f
            }
            .background(Color(0xFF000000))
    ) {
        // TOP BAR
        TopHeader(
            gpsLevel = gpsSignalLevel,
            accuracyMeters = accuracyMeters,
            onOpenDrawer = {},
            onOpenSettings = { showSettingsDialog = true }
        )

        // SUB-HEADER ROW: [RESET ↺] [ELAPSED TIME PILL] [HUD MODE]
        SubHeaderControls(
            elapsedSeconds = tripElapsedSeconds,
            isHudMirror = isHudMirror,
            onResetClick = { showResetDialog = true },
            onToggleHud = { isHudMirror = !isHudMirror }
        )

        // MAIN CONTENT BODY
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            if (!hasLocationPermission) {
                GpsPermissionCard(onRequestPermission = {
                    permissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                })
            }

            // OVER SPEED ALERT BANNER
            AnimatedVisibility(
                visible = isOverSpeed,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Surface(
                    color = Color(0xFFDC2626).copy(alpha = pulseAlpha),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Text(
                        text = "⚠️ OVER SPEED! LIMIT: ${activeSpeedLimitInUnit.roundToInt()} ${unit.unitText}",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            }

            when (activeTab) {
                NavigationTab.ANALOG -> {
                    ReferenceSpeedometerDial(
                        speed = animatedSpeed,
                        rawSpeed = displaySpeed,
                        unitText = unit.unitText,
                        maxGauge = unit.maxGaugeValue,
                        isOverSpeed = isOverSpeed
                    )
                }
                NavigationTab.DIGITAL -> {
                    DigitalSpeedDisplay(
                        rawSpeed = displaySpeed,
                        unitText = unit.unitText,
                        isOverSpeed = isOverSpeed
                    )
                }
                NavigationTab.MAP -> {
                    GpsMetricsDisplay(
                        altitudeMeters = altitudeMeters,
                        accuracyMeters = accuracyMeters,
                        bearingDegrees = bearingDegrees,
                        hasBearing = hasBearing,
                        latitude = latitude,
                        longitude = longitude
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 3-COLUMN TELEMETRY PANEL
            ReferenceTelemetryPanel(
                maxSpeed = String.format(Locale.US, "%.0f", maxDisplaySpeed),
                distance = String.format(Locale.US, "%.1f", displayDistance),
                avgSpeed = String.format(Locale.US, "%.0f", avgSpeedDisplay),
                speedUnit = unit.unitText,
                distanceUnit = unit.distanceUnitText
            )

            Spacer(modifier = Modifier.height(14.dp))

            // PRIMARY CYAN "START / STOP" BUTTON
            PrimaryActionButton(
                isRunning = isTripRunning,
                onClick = { isTripRunning = !isTripRunning }
            )

            Spacer(modifier = Modifier.height(14.dp))
        }

        // BOTTOM NAVIGATION BAR
        BottomNavigationBar(
            selectedTab = activeTab,
            onTabSelected = { activeTab = it }
        )
    }

    // SETTINGS DIALOG
    if (showSettingsDialog) {
        SettingsDialog(
            currentUnit = unit,
            currentLimitKmh = speedLimitKmh,
            onSelectUnit = {
                unit = it
                prefs.edit().putString("unit", it.name).apply()
            },
            onSelectLimit = {
                speedLimitKmh = it
                prefs.edit().putFloat("speed_limit_kmh", it).apply()
            },
            onDismiss = { showSettingsDialog = false }
        )
    }

    // RESET TRIP CONFIRMATION DIALOG
    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("Reset Trip Data?") },
            text = { Text("Max Speed, Distance, and Elapsed Time will be reset to 0.") },
            confirmButton = {
                Button(
                    onClick = {
                        maxSpeedMps = 0f
                        totalDistanceMeters = 0.0
                        tripElapsedSeconds = 0L
                        movingSeconds = 0L
                        showResetDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF), contentColor = Color(0xFF000000))
                ) {
                    Text("Reset", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text("Cancel", color = Color.White)
                }
            }
        )
    }
}
