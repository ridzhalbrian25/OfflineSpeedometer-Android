package com.example.offlinespeedometer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Paint
import android.graphics.Typeface
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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

enum class SpeedUnit(
    val label: String,
    val unitText: String,
    val distanceUnitText: String,
    val speedMultiplier: Float,     // dari m/s
    val distanceMultiplier: Double, // dari meter
    val maxGaugeValue: Int
) {
    METRIC("KM/H", "Kmh", "Km", 3.6f, 0.001, 220),
    IMPERIAL("MPH", "Mph", "Mi", 2.236936f, 0.000621371, 140),
    KNOTS("KNOTS", "Kn", "Nm", 1.943844f, 0.000539957, 120)
}

enum class NavigationTab(val title: String) {
    ANALOG("Analog"),
    DIGITAL("Digital"),
    MAP("Map")
}

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

    // Haptic vibration helper
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

    // Timer Coroutine
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

    // Location Updates
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

    // Conversions
    val displaySpeed = currentSpeedMps * unit.speedMultiplier
    val maxDisplaySpeed = maxSpeedMps * unit.speedMultiplier
    val displayDistance = totalDistanceMeters * unit.distanceMultiplier
    val avgSpeedDisplay = if (movingSeconds > 3L) {
        (totalDistanceMeters / movingSeconds.toDouble() * unit.speedMultiplier).toFloat()
    } else 0f

    // GPS Signal strength level (0 to 4 bars)
    val gpsSignalLevel = when {
        !hasLocationPermission || gpsFixCount == 0 -> 0
        accuracyMeters in 0.1f..5.0f -> 4   // Sangat Kuat
        accuracyMeters in 5.0f..12.0f -> 3  // Kuat
        accuracyMeters in 12.0f..30.0f -> 2 // Sedang
        accuracyMeters > 30.0f -> 1         // Lemah
        else -> 1
    }

    // Speed limit alert
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

    // Animated speed for needle & gauge
    val animatedSpeed by animateFloatAsState(
        targetValue = displaySpeed,
        animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
        label = "animatedSpeed"
    )

    // Pulse animation for over-speed
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
        // TOP BAR WITH GPS SIGNAL STRENGTH INDICATOR
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

        // MAIN CONTENT BODY (Scrollable for compact screens)
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
                    // EXACT REFERENCE 1:1 ANALOG DIAL
                    ReferenceSpeedometerDial(
                        speed = animatedSpeed,
                        rawSpeed = displaySpeed,
                        unitText = unit.unitText,
                        maxGauge = unit.maxGaugeValue,
                        isOverSpeed = isOverSpeed
                    )
                }
                NavigationTab.DIGITAL -> {
                    // DIGITAL HUD DISPLAY
                    DigitalSpeedDisplay(
                        rawSpeed = displaySpeed,
                        unitText = unit.unitText,
                        isOverSpeed = isOverSpeed
                    )
                }
                NavigationTab.MAP -> {
                    // GPS METRICS & COORDS VIEW
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

            // 3-COLUMN TELEMETRY PANEL: Max Speed | Distance | Avg Speed
            ReferenceTelemetryPanel(
                maxSpeed = String.format("%.0f", maxDisplaySpeed),
                distance = String.format("%.1f", displayDistance),
                avgSpeed = String.format("%.0f", avgSpeedDisplay),
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

        // BOTTOM NAVIGATION BAR (Analog | Digital | Map)
        BottomNavigationBar(
            selectedTab = activeTab,
            onTabSelected = { activeTab = it }
        )
    }

    // SETTINGS DIALOG (Units & Speed Limit)
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

    // RESET CONFIRMATION DIALOG
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

// ==========================================
// 1. TOP HEADER WITH GPS SIGNAL STRENGTH BARS
// ==========================================
@Composable
fun TopHeader(
    gpsLevel: Int,
    accuracyMeters: Float,
    onOpenDrawer: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onOpenDrawer, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = Icons.Default.Menu,
                contentDescription = "Menu",
                tint = Color.White
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "GPS Speedometer",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )

            // GPS SIGNAL STRENGTH INDICATOR (4-BAR ANTENNA + BADGE)
            GpsSignalStrengthBars(level = gpsLevel, accuracyMeters = accuracyMeters)
        }

        IconButton(onClick = onOpenSettings, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = "Settings",
                tint = Color.White
            )
        }
    }
}

// ==========================================
// GPS SIGNAL STRENGTH BARS COMPONENT (▂▄▆█)
// ==========================================
@Composable
fun GpsSignalStrengthBars(
    level: Int, // 0 to 4
    accuracyMeters: Float
) {
    val barColor = when (level) {
        4 -> Color(0xFF00E5FF) // Sangat Kuat (Cyan)
        3 -> Color(0xFF10B981) // Kuat (Hijau)
        2 -> Color(0xFFF59E0B) // Sedang (Kuning/Amber)
        1 -> Color(0xFFEF4444) // Lemah (Merah)
        else -> Color(0xFF525861) // Offline / Mencari (Abu-abu)
    }

    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .padding(start = 2.dp)
            .height(16.dp)
    ) {
        // Bar 1 (Height 4dp)
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(if (level >= 1) barColor else Color(0xFF333842))
        )
        // Bar 2 (Height 7dp)
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(7.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(if (level >= 2) barColor else Color(0xFF333842))
        )
        // Bar 3 (Height 10dp)
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(10.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(if (level >= 3) barColor else Color(0xFF333842))
        )
        // Bar 4 (Height 14dp)
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(14.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(if (level >= 4) barColor else Color(0xFF333842))
        )
    }
}

// ==========================================
// 2. SUB-HEADER CONTROLS (RESET, TIMER PILL, HUD)
// ==========================================
@Composable
fun SubHeaderControls(
    elapsedSeconds: Long,
    isHudMirror: Boolean,
    onResetClick: () -> Unit,
    onToggleHud: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // RESET BUTTON (Dark circle with ↺)
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Color(0xFF212327))
                .clickable { onResetClick() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = "Reset Trip",
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        }

        // ELAPSED TIME PILL: [ 0:00:24 s ]
        Surface(
            color = Color(0xFF212327),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.height(38.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = formatTimerDuration(elapsedSeconds),
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp
                )
                Text(
                    text = "s",
                    color = Color(0xFF9EA5AF),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Normal
                )
            }
        }

        // HUD FLIP BUTTON (Dark circle)
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(if (isHudMirror) Color(0xFF00E5FF) else Color(0xFF212327))
                .clickable { onToggleHud() },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "HUD",
                color = if (isHudMirror) Color(0xFF000000) else Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.ExtraBold
            )
        }
    }
}

// ==========================================
// 3. EXACT 1:1 REFERENCE SPEEDOMETER DIAL (CANVAS)
// ==========================================
@Composable
fun ReferenceSpeedometerDial(
    speed: Float,
    rawSpeed: Float,
    unitText: String,
    maxGauge: Int,
    isOverSpeed: Boolean
) {
    val fraction = (speed / maxGauge).coerceIn(0f, 1f)

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .height(320.dp)
            .padding(top = 8.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f - 10.dp.toPx())
            val strokeWidth = 18.dp.toPx()
            val outerRadius = (size.minDimension / 2f) - strokeWidth - 14.dp.toPx()

            val arcTopLeft = Offset(center.x - outerRadius, center.y - outerRadius)
            val arcSize = Size(outerRadius * 2f, outerRadius * 2f)

            // A. INACTIVE TRACK ARC (#525861)
            drawArc(
                color = Color(0xFF525861),
                startAngle = 135f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = arcTopLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )

            // B. ACTIVE TRACK ARC (#00E5FF Neon Cyan)
            if (fraction > 0.005f) {
                drawArc(
                    color = if (isOverSpeed) Color(0xFFEF4444) else Color(0xFF00E5FF),
                    startAngle = 135f,
                    sweepAngle = 270f * fraction,
                    useCenter = false,
                    topLeft = arcTopLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )

                // C. GLOWING LEAD INDICATOR DOT AT ACTIVE TIP
                val leadAngleDeg = 135.0 + (270.0 * fraction)
                val leadRad = Math.toRadians(leadAngleDeg)
                val leadCenter = Offset(
                    (center.x + outerRadius * cos(leadRad)).toFloat(),
                    (center.y + outerRadius * sin(leadRad)).toFloat()
                )

                // Outer glow
                drawCircle(
                    color = (if (isOverSpeed) Color(0xFFEF4444) else Color(0xFF00E5FF)).copy(alpha = 0.45f),
                    radius = strokeWidth * 0.9f,
                    center = leadCenter
                )
                // Inner bright core
                drawCircle(
                    color = Color(0xFFE0FFFF),
                    radius = strokeWidth * 0.35f,
                    center = leadCenter
                )
            }

            // D. RADIAL TICKS AND CYAN NUMBERS (0, 20, 40, ... 220)
            val step = 20
            val totalTicks = maxGauge / step
            val textPaint = Paint().apply {
                color = android.graphics.Color.parseColor("#3DE1FF")
                textSize = 11.5.dp.toPx()
                textAlign = Paint.Align.CENTER
                typeface = Typeface.DEFAULT_BOLD
                isAntiAlias = true
            }

            for (i in 0..totalTicks) {
                val currentVal = i * step
                val tickFraction = currentVal.toFloat() / maxGauge
                val tickAngleDeg = 135.0 + (270.0 * tickFraction)
                val tickRad = Math.toRadians(tickAngleDeg)

                // Major white tick line
                val tickOuterR = outerRadius - (strokeWidth / 2f) - 6.dp.toPx()
                val tickInnerR = tickOuterR - 8.dp.toPx()

                val tickStart = Offset(
                    (center.x + tickOuterR * cos(tickRad)).toFloat(),
                    (center.y + tickOuterR * sin(tickRad)).toFloat()
                )
                val tickEnd = Offset(
                    (center.x + tickInnerR * cos(tickRad)).toFloat(),
                    (center.y + tickInnerR * sin(tickRad)).toFloat()
                )

                drawLine(
                    color = Color.White,
                    start = tickStart,
                    end = tickEnd,
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Square
                )

                // Dial Numerals (0, 20, 40, 60 ... 220) in #3DE1FF
                val numRadius = tickInnerR - 11.dp.toPx()
                val nx = (center.x + numRadius * cos(tickRad)).toFloat()
                val ny = (center.y + numRadius * sin(tickRad)).toFloat()

                drawIntoCanvas { canvas ->
                    val fm = textPaint.fontMetrics
                    val yOffset = (fm.descent + fm.ascent) / 2f
                    canvas.nativeCanvas.drawText(
                        currentVal.toString(),
                        nx,
                        ny - yOffset,
                        textPaint
                    )
                }

                // Minor intermediate tick between major ticks (if not last)
                if (i < totalTicks) {
                    val halfAngleDeg = tickAngleDeg + (270.0 / totalTicks / 2.0)
                    val halfRad = Math.toRadians(halfAngleDeg)
                    val halfStart = Offset(
                        (center.x + tickOuterR * cos(halfRad)).toFloat(),
                        (center.y + tickOuterR * sin(halfRad)).toFloat()
                    )
                    val halfEnd = Offset(
                        (center.x + (tickOuterR - 4.dp.toPx()) * cos(halfRad)).toFloat(),
                        (center.y + (tickOuterR - 4.dp.toPx()) * sin(halfRad)).toFloat()
                    )
                    drawLine(
                        color = Color(0xFF717985),
                        start = halfStart,
                        end = halfEnd,
                        strokeWidth = 1.2.dp.toPx(),
                        cap = StrokeCap.Square
                    )
                }
            }

            // E. SHARP TAPERED WHITE NEEDLE
            val needleAngleDeg = 135.0 + (270.0 * fraction)
            val needleRad = Math.toRadians(needleAngleDeg)
            val perpRad = needleRad + Math.PI / 2.0

            val needleLength = outerRadius - strokeWidth - 14.dp.toPx()
            val needleBaseWidth = 5.dp.toPx()

            val tip = Offset(
                (center.x + needleLength * cos(needleRad)).toFloat(),
                (center.y + needleLength * sin(needleRad)).toFloat()
            )
            val baseLeft = Offset(
                (center.x + needleBaseWidth * cos(perpRad)).toFloat(),
                (center.y + needleBaseWidth * sin(perpRad)).toFloat()
            )
            val baseRight = Offset(
                (center.x - needleBaseWidth * cos(perpRad)).toFloat(),
                (center.y - needleBaseWidth * sin(perpRad)).toFloat()
            )

            val needlePath = Path().apply {
                moveTo(tip.x, tip.y)
                lineTo(baseRight.x, baseRight.y)
                lineTo(baseLeft.x, baseLeft.y)
                close()
            }

            drawPath(path = needlePath, color = Color.White)

            // F. CENTER HUB PIVOT (Outer white ring + dark center hole)
            drawCircle(
                color = Color.White,
                radius = 12.dp.toPx(),
                center = center
            )
            drawCircle(
                color = Color(0xFF15171B),
                radius = 7.dp.toPx(),
                center = center
            )
        }

        // G. DIGITAL SPEED READOUT INSIDE BOTTOM OPENING
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(top = 110.dp)
        ) {
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = rawSpeed.roundToInt().toString(),
                    fontSize = 56.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.SansSerif,
                    color = if (isOverSpeed) Color(0xFFEF4444) else Color(0xFF00E5FF),
                    letterSpacing = (-1).sp
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = unitText,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            Text(
                text = "Current Speed",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

// ==========================================
// 4. TELEMETRY PANEL (3 COLUMNS: MAX SPEED, DISTANCE, AVG SPEED)
// ==========================================
@Composable
fun ReferenceTelemetryPanel(
    maxSpeed: String,
    distance: String,
    avgSpeed: String,
    speedUnit: String,
    distanceUnit: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TelemetryCard(
            label = "Max Speed",
            value = maxSpeed,
            unit = speedUnit,
            modifier = Modifier.weight(1f)
        )
        TelemetryCard(
            label = "Distance",
            value = distance,
            unit = distanceUnit,
            modifier = Modifier.weight(1f)
        )
        TelemetryCard(
            label = "Avg Speed",
            value = avgSpeed,
            unit = speedUnit,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun TelemetryCard(
    label: String,
    value: String,
    unit: String,
    modifier: Modifier = Modifier
) {
    Surface(
        color = Color(0xFF191B1F),
        shape = RoundedCornerShape(10.dp),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = label,
                color = Color(0xFF9EA5AF),
                fontSize = 12.sp,
                fontWeight = FontWeight.Normal
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = value,
                    color = Color(0xFF00E5FF),
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.SansSerif
                )
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    text = unit,
                    color = Color(0xFFC4C9D1),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(bottom = 3.dp)
                )
            }
        }
    }
}

// ==========================================
// 5. PRIMARY ACTION BUTTON (CYAN "START / STOP")
// ==========================================
@Composable
fun PrimaryActionButton(
    isRunning: Boolean,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFF00E5FF),
            contentColor = Color(0xFF000000)
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = null,
                tint = Color(0xFF000000),
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (isRunning) "Stop" else "Start",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF000000)
            )
        }
    }
}

// ==========================================
// 6. BOTTOM NAVIGATION BAR (ANALOG, DIGITAL, MAP)
// ==========================================
@Composable
fun BottomNavigationBar(
    selectedTab: NavigationTab,
    onTabSelected: (NavigationTab) -> Unit
) {
    Surface(
        color = Color(0xFF000000),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            NavigationTab.entries.forEach { tab ->
                val isSelected = selectedTab == tab
                Column(
                    modifier = Modifier
                        .clickable { onTabSelected(tab) }
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = tab.title,
                        color = if (isSelected) Color(0xFF00E5FF) else Color(0xFF9EA5AF),
                        fontSize = 14.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    if (isSelected) {
                        Box(
                            modifier = Modifier
                                .width(36.dp)
                                .height(3.dp)
                                .clip(RoundedCornerShape(1.5.dp))
                                .background(Color(0xFF00E5FF))
                        )
                    } else {
                        Spacer(modifier = Modifier.height(3.dp))
                    }
                }
            }
        }
    }
}

// ==========================================
// DIGITAL SPEED DISPLAY VIEW
// ==========================================
@Composable
fun DigitalSpeedDisplay(
    rawSpeed: Float,
    unitText: String,
    isOverSpeed: Boolean
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = rawSpeed.roundToInt().toString(),
                fontSize = 110.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.SansSerif,
                color = if (isOverSpeed) Color(0xFFEF4444) else Color(0xFF00E5FF),
                letterSpacing = (-4).sp
            )
            Text(
                text = unitText,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Current Speed",
                color = Color(0xFF9EA5AF),
                fontSize = 15.sp
            )
        }
    }
}

// ==========================================
// GPS METRICS & COORDS VIEW
// ==========================================
@Composable
fun GpsMetricsDisplay(
    altitudeMeters: Double,
    accuracyMeters: Float,
    bearingDegrees: Float,
    hasBearing: Boolean,
    latitude: Double,
    longitude: Double
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp)
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            color = Color(0xFF191B1F),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Altitude", color = Color(0xFF9EA5AF), fontSize = 14.sp)
                    Text(String.format("%.0f m ASL", altitudeMeters), color = Color(0xFF00E5FF), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Heading", color = Color(0xFF9EA5AF), fontSize = 14.sp)
                    Text(if (hasBearing) String.format("%.0f°", bearingDegrees) else "—", color = Color(0xFF00E5FF), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("GPS Precision", color = Color(0xFF9EA5AF), fontSize = 14.sp)
                    Text(if (accuracyMeters > 0f) String.format("±%.0f m", accuracyMeters) else "Searching...", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Coordinates", color = Color(0xFF9EA5AF), fontSize = 14.sp)
                    Text(String.format("%.4f°, %.4f°", latitude, longitude), color = Color(0xFFCBD5E1), fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

// ==========================================
// PERMISSION CARD
// ==========================================
@Composable
fun GpsPermissionCard(onRequestPermission: () -> Unit) {
    Surface(
        color = Color(0xFF191B1F),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "GPS Permission Required for Offline Speedometer",
                color = Color.White,
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(6.dp))
            Button(
                onClick = onRequestPermission,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF), contentColor = Color(0xFF000000))
            ) {
                Text("Enable GPS Sensor", fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ==========================================
// SETTINGS DIALOG (UNITS & SPEED LIMIT)
// ==========================================
@Composable
fun SettingsDialog(
    currentUnit: SpeedUnit,
    currentLimitKmh: Float,
    onSelectUnit: (SpeedUnit) -> Unit,
    onSelectLimit: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    val limits = listOf(0f, 60f, 80f, 100f, 120f)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Speedometer Settings", color = Color.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Speed Unit", color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SpeedUnit.entries.forEach { u ->
                        val isSelected = u == currentUnit
                        Surface(
                            color = if (isSelected) Color(0xFF00E5FF) else Color(0xFF212327),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onSelectUnit(u) }
                        ) {
                            Text(
                                text = u.label,
                                color = if (isSelected) Color(0xFF000000) else Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                    }
                }

                Text("Over Speed Warning", color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    limits.forEach { lim ->
                        val isSelected = lim == currentLimitKmh
                        val text = if (lim == 0f) "OFF (No Alert)" else "${lim.roundToInt()} km/h"
                        Surface(
                            color = if (isSelected) Color(0xFF00E5FF).copy(alpha = 0.2f) else Color(0xFF212327),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectLimit(lim) }
                                .border(
                                    1.dp,
                                    if (isSelected) Color(0xFF00E5FF) else Color.Transparent,
                                    RoundedCornerShape(8.dp)
                                )
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(text, color = if (isSelected) Color(0xFF00E5FF) else Color.White)
                                if (isSelected) Text("✓", color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF), contentColor = Color(0xFF000000))
            ) {
                Text("Done", fontWeight = FontWeight.Bold)
            }
        }
    )
}

// ==========================================
// UTILITIES
// ==========================================
fun formatTimerDuration(totalSeconds: Long): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return String.format("%d:%02d:%02d", hours, minutes, seconds)
}
