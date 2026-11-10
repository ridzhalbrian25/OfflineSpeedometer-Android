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
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
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
    val distanceLabel: String,
    val altitudeLabel: String,
    val speedMultiplier: Float,     // dari m/s
    val distanceMultiplier: Double, // dari meter
    val altitudeMultiplier: Double, // dari meter
    val maxGaugeValue: Int,
    val tickStep: Int
) {
    METRIC("km/h", "km", "m", 3.6f, 0.001, 1.0, 240, 20),
    IMPERIAL("mph", "mi", "ft", 2.236936f, 0.000621371, 3.28084, 160, 20),
    KNOTS("knots", "nm", "ft", 1.943844f, 0.000539957, 3.28084, 140, 20)
}

enum class CockpitTheme(
    val title: String,
    val primary: Color,
    val accent: Color,
    val warning: Color
) {
    CYBER_EMERALD("Emerald", Color(0xFF10B981), Color(0xFF00F5D4), Color(0xFFEF4444)),
    ELECTRIC_CYAN("Cyan", Color(0xFF06B6D4), Color(0xFF38BDF8), Color(0xFFF43F5E)),
    SOLAR_AMBER("Amber", Color(0xFFF59E0B), Color(0xFFFBBF24), Color(0xFFEF4444)),
    PURE_OLED("OLED White", Color(0xFFF8FAFC), Color(0xFF94A3B8), Color(0xFFFF3B30))
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            OfflineSpeedometerApp()
        }
    }
}

@Composable
fun OfflineSpeedometerApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("speedometer_prefs", Context.MODE_PRIVATE) }

    var unit by remember {
        val saved = prefs.getString("unit", SpeedUnit.METRIC.name) ?: SpeedUnit.METRIC.name
        mutableStateOf(try { SpeedUnit.valueOf(saved) } catch (_: Exception) { SpeedUnit.METRIC })
    }

    var selectedTheme by remember {
        val saved = prefs.getString("theme", CockpitTheme.CYBER_EMERALD.name) ?: CockpitTheme.CYBER_EMERALD.name
        mutableStateOf(try { CockpitTheme.valueOf(saved) } catch (_: Exception) { CockpitTheme.CYBER_EMERALD })
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

    var currentSpeedMps by remember { mutableFloatStateOf(0f) }
    var maxSpeedMps by remember { mutableFloatStateOf(0f) }
    var totalDistanceMeters by remember { mutableDoubleStateOf(0.0) }
    var altitudeMeters by remember { mutableDoubleStateOf(0.0) }
    var accuracyMeters by remember { mutableFloatStateOf(0f) }
    var bearingDegrees by remember { mutableFloatStateOf(0f) }
    var hasBearing by remember { mutableStateOf(false) }
    var latitude by remember { mutableDoubleStateOf(0.0) }
    var longitude by remember { mutableDoubleStateOf(0.0) }
    var hasValidCoords by remember { mutableStateOf(false) }

    var isHudMirror by remember { mutableStateOf(false) }
    var isTripPaused by remember { mutableStateOf(false) }
    var tripElapsedSeconds by remember { mutableLongStateOf(0L) }
    var movingSeconds by remember { mutableLongStateOf(0L) }
    var showResetDialog by remember { mutableStateOf(false) }
    var showSpeedLimitDialog by remember { mutableStateOf(false) }
    var lastLocation by remember { mutableStateOf<Location?>(null) }
    var gpsFixCount by remember { mutableIntStateOf(0) }

    // Haptic / vibration helper
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
    LaunchedEffect(isTripPaused) {
        while (true) {
            delay(1000L)
            if (!isTripPaused) {
                tripElapsedSeconds += 1L
                if (currentSpeedMps > 0.5f) {
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

                if (!isTripPaused) {
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
                hasValidCoords = true
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

    // Calculations
    val displaySpeed = currentSpeedMps * unit.speedMultiplier
    val maxDisplaySpeed = maxSpeedMps * unit.speedMultiplier
    val displayDistance = totalDistanceMeters * unit.distanceMultiplier
    val displayAltitude = altitudeMeters * unit.altitudeMultiplier
    val displayAccuracy = accuracyMeters * unit.altitudeMultiplier

    val avgSpeedDisplay = if (movingSeconds > 5L) {
        (totalDistanceMeters / movingSeconds.toDouble() * unit.speedMultiplier).toFloat()
    } else 0f

    // Speed limit evaluation
    val activeSpeedLimitInUnit = if (speedLimitKmh > 0f) {
        speedLimitKmh * (unit.speedMultiplier / 3.6f)
    } else 0f
    val isOverSpeed = activeSpeedLimitInUnit > 0f && displaySpeed > activeSpeedLimitInUnit

    // Over speed vibration alert
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

    // Animated speed value for ultra-smooth needle movement
    val animatedSpeed by animateFloatAsState(
        targetValue = displaySpeed,
        animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
        label = "animatedSpeed"
    )

    // Pulse animation for over-speed warning
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

    val bgColor = if (isHudMirror) Color(0xFF000000) else Color(0xFF070B14)

    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = if (isHudMirror) -1f else 1f
                },
            color = bgColor
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // TOP APP BAR & STATUS
                TopHeaderBar(
                    unit = unit,
                    selectedTheme = selectedTheme,
                    isHudMirror = isHudMirror,
                    accuracyMeters = accuracyMeters,
                    gpsFixCount = gpsFixCount,
                    hasPermission = hasLocationPermission,
                    onUnitChange = { newUnit ->
                        unit = newUnit
                        prefs.edit().putString("unit", newUnit.name).apply()
                    },
                    onThemeChange = { newTheme ->
                        selectedTheme = newTheme
                        prefs.edit().putString("theme", newTheme.name).apply()
                    },
                    onRequestPermission = {
                        permissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                            )
                        )
                    }
                )

                // OVER SPEED WARNING BANNER (When exceeded)
                AnimatedVisibility(
                    visible = isOverSpeed,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Surface(
                        color = Color(0xFFDC2626).copy(alpha = pulseAlpha),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.5.dp, Color(0xFFEF4444), RoundedCornerShape(12.dp))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = "⚠️ PERINGATAN: MELEBIHI BATAS KECEPATAN (${activeSpeedLimitInUnit.roundToInt()} ${unit.label.uppercase()})",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                // SPEEDOMETER GAUGE DIAL (The Hero Component)
                SpeedometerGauge(
                    speed = animatedSpeed,
                    rawSpeed = displaySpeed,
                    maxGauge = unit.maxGaugeValue,
                    tickStep = unit.tickStep,
                    unitLabel = unit.label.uppercase(),
                    theme = selectedTheme,
                    isOverSpeed = isOverSpeed,
                    pulseAlpha = pulseAlpha,
                    speedLimit = activeSpeedLimitInUnit
                )

                // COMPREHENSIVE TELEMETRY METRICS GRID (6 Cards)
                TelemetryGrid(
                    maxSpeed = String.format("%.1f %s", maxDisplaySpeed, unit.label),
                    avgSpeed = String.format("%.1f %s", avgSpeedDisplay, unit.label),
                    distance = String.format("%.2f %s", displayDistance, unit.distanceLabel),
                    tripTime = formatDuration(tripElapsedSeconds),
                    altitude = String.format("%.0f %s", displayAltitude, unit.altitudeLabel),
                    heading = if (hasBearing) getDirectionLabel(bearingDegrees) else "—",
                    accuracy = if (accuracyMeters > 0f) String.format("±%.0f %s", displayAccuracy, unit.altitudeLabel) else "Mencari GPS...",
                    coords = if (hasValidCoords) String.format("%.4f°, %.4f°", latitude, longitude) else "—",
                    theme = selectedTheme
                )

                // BOTTOM ACTION CONTROLS (HUD, Speed Limit, Trip Pause, Reset)
                ControlActionBar(
                    isHudMirror = isHudMirror,
                    isTripPaused = isTripPaused,
                    activeLimitKmh = speedLimitKmh,
                    theme = selectedTheme,
                    onToggleHud = { isHudMirror = !isHudMirror },
                    onTogglePause = { isTripPaused = !isTripPaused },
                    onOpenSpeedLimit = { showSpeedLimitDialog = true },
                    onResetClick = { showResetDialog = true }
                )

                Spacer(modifier = Modifier.height(10.dp))
            }

            // RESET CONFIRMATION DIALOG
            if (showResetDialog) {
                AlertDialog(
                    onDismissRequest = { showResetDialog = false },
                    title = { Text("Reset Data Perjalanan?") },
                    text = { Text("Kecepatan maksimum, jarak tempuh, dan waktu perjalanan akan diatur ulang ke nol.") },
                    confirmButton = {
                        Button(
                            onClick = {
                                maxSpeedMps = 0f
                                totalDistanceMeters = 0.0
                                tripElapsedSeconds = 0L
                                movingSeconds = 0L
                                showResetDialog = false
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))
                        ) {
                            Text("Reset Sekarang")
                        }
                    },
                    dismissButton = {
                        OutlinedButton(onClick = { showResetDialog = false }) {
                            Text("Batal")
                        }
                    }
                )
            }

            // SPEED LIMIT SELECTOR DIALOG
            if (showSpeedLimitDialog) {
                SpeedLimitDialog(
                    currentLimitKmh = speedLimitKmh,
                    unit = unit,
                    onSelectLimit = { chosenKmh ->
                        speedLimitKmh = chosenKmh
                        prefs.edit().putFloat("speed_limit_kmh", chosenKmh).apply()
                        showSpeedLimitDialog = false
                    },
                    onDismiss = { showSpeedLimitDialog = false }
                )
            }
        }
    }
}

// ==========================================
// TOP HEADER BAR & CONTROLS
// ==========================================
@Composable
fun TopHeaderBar(
    unit: SpeedUnit,
    selectedTheme: CockpitTheme,
    isHudMirror: Boolean,
    accuracyMeters: Float,
    gpsFixCount: Int,
    hasPermission: Boolean,
    onUnitChange: (SpeedUnit) -> Unit,
    onThemeChange: (CockpitTheme) -> Unit,
    onRequestPermission: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                !hasPermission -> Color(0xFFEF4444)
                                accuracyMeters in 0.1f..15.0f -> Color(0xFF10B981)
                                gpsFixCount > 0 -> Color(0xFFF59E0B)
                                else -> Color(0xFF64748B)
                            }
                        )
                )
                Text(
                    text = "GPS SPEEDOMETER",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.2.sp
                )
            }

            val statusText = when {
                !hasPermission -> "Izin GPS Diperlukan"
                accuracyMeters in 0.1f..15.0f -> "Satelit Terkunci (±${accuracyMeters.roundToInt()}m)"
                gpsFixCount > 0 -> "Mencari Sinyal Satelit..."
                else -> "Sensor GPS Offline Aktif"
            }
            Text(
                text = statusText,
                color = if (!hasPermission) Color(0xFFEF4444) else Color(0xFF94A3B8),
                fontSize = 11.sp,
                modifier = if (!hasPermission) Modifier.clickable { onRequestPermission() } else Modifier
            )
        }

        // UNIT SWITCHER PILL (KM/H, MPH, KNOTS)
        Row(
            modifier = Modifier
                .background(Color(0xFF131C2E), RoundedCornerShape(10.dp))
                .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(10.dp))
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            SpeedUnit.entries.forEach { item ->
                val selected = unit == item
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(7.dp))
                        .background(if (selected) selectedTheme.primary else Color.Transparent)
                        .clickable { onUnitChange(item) }
                        .padding(horizontal = 9.dp, vertical = 5.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = item.label.uppercase(),
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        fontSize = 11.sp,
                        color = if (selected) Color(0xFF070B14) else Color(0xFF94A3B8)
                    )
                }
            }
        }
    }
}

// ==========================================
// HERO SPEEDOMETER GAUGE DIAL (CANVAS ENGINE)
// ==========================================
@Composable
fun SpeedometerGauge(
    speed: Float,
    rawSpeed: Float,
    maxGauge: Int,
    tickStep: Int,
    unitLabel: String,
    theme: CockpitTheme,
    isOverSpeed: Boolean,
    pulseAlpha: Float,
    speedLimit: Float
) {
    val fraction = (speed / maxGauge).coerceIn(0f, 1f)

    val activeArcColor by animateColorAsState(
        targetValue = when {
            isOverSpeed -> Color(0xFFEF4444)
            fraction > 0.75f -> Color(0xFFEF4444)
            fraction > 0.45f -> Color(0xFFF59E0B)
            else -> theme.primary
        },
        label = "arcColor"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .height(310.dp)
            .padding(4.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 14.dp.toPx()
            val center = Offset(size.width / 2f, size.height / 2f)
            val outerRadius = (size.minDimension / 2f) - strokeWidth - 10.dp.toPx()

            val arcTopLeft = Offset(center.x - outerRadius, center.y - outerRadius)
            val arcSize = Size(outerRadius * 2f, outerRadius * 2f)

            // 1. DIAL INNER GLOW / BACKGROUND CIRCLE
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF0F172A).copy(alpha = 0.7f),
                        Color(0xFF070B14).copy(alpha = 0.95f)
                    ),
                    center = center,
                    radius = outerRadius + strokeWidth
                ),
                radius = outerRadius + strokeWidth,
                center = center
            )

            // 2. BACKGROUND TRACK ARC (Dark Gunmetal Ring)
            drawArc(
                color = Color(0xFF1E293B),
                startAngle = 135f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = arcTopLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )

            // 3. SPEED LIMIT MARKER (Red tick along track if configured)
            if (speedLimit in 1f..maxGauge.toFloat()) {
                val limitFraction = (speedLimit / maxGauge).coerceIn(0f, 1f)
                val limitAngleDeg = 135.0 + (270.0 * limitFraction)
                val limitRad = Math.toRadians(limitAngleDeg)
                val mInner = outerRadius - strokeWidth * 0.9f
                val mOuter = outerRadius + strokeWidth * 0.9f
                drawLine(
                    color = Color(0xFFEF4444),
                    start = Offset((center.x + mInner * cos(limitRad)).toFloat(), (center.y + mInner * sin(limitRad)).toFloat()),
                    end = Offset((center.x + mOuter * cos(limitRad)).toFloat(), (center.y + mOuter * sin(limitRad)).toFloat()),
                    strokeWidth = 3.5.dp.toPx(),
                    cap = StrokeCap.Square
                )
            }

            // 4. ACTIVE PROGRESS ARC (Dynamic Glow)
            if (fraction > 0.005f) {
                drawArc(
                    brush = Brush.sweepGradient(
                        colors = listOf(
                            theme.accent,
                            activeArcColor,
                            if (isOverSpeed) Color(0xFFEF4444) else activeArcColor
                        ),
                        center = center
                    ),
                    startAngle = 135f,
                    sweepAngle = 270f * fraction,
                    useCenter = false,
                    topLeft = arcTopLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
            }

            // 5. GRADUATED TICKS AND SPEED NUMERALS
            val totalTicks = (maxGauge / (tickStep / 2))
            val textPaint = Paint().apply {
                color = android.graphics.Color.argb(190, 203, 213, 225)
                textSize = 10.5.dp.toPx()
                textAlign = Paint.Align.CENTER
                typeface = Typeface.DEFAULT_BOLD
                isAntiAlias = true
            }

            for (i in 0..totalTicks) {
                val currentTickVal = i * (tickStep / 2)
                val tickFraction = currentTickVal.toFloat() / maxGauge
                val tickAngleDeg = 135.0 + (270.0 * tickFraction)
                val tickRad = Math.toRadians(tickAngleDeg)

                val isMajor = (currentTickVal % tickStep) == 0
                val tickLength = if (isMajor) 11.dp.toPx() else 6.dp.toPx()
                val tickWidth = if (isMajor) 2.2.dp.toPx() else 1.2.dp.toPx()

                val tickOuterR = outerRadius - (strokeWidth / 2f) - 4.dp.toPx()
                val tickInnerR = tickOuterR - tickLength

                val start = Offset(
                    (center.x + tickOuterR * cos(tickRad)).toFloat(),
                    (center.y + tickOuterR * sin(tickRad)).toFloat()
                )
                val end = Offset(
                    (center.x + tickInnerR * cos(tickRad)).toFloat(),
                    (center.y + tickInnerR * sin(tickRad)).toFloat()
                )

                drawLine(
                    color = if (isMajor) Color(0xFF94A3B8) else Color(0xFF475569),
                    start = start,
                    end = end,
                    strokeWidth = tickWidth,
                    cap = StrokeCap.Round
                )

                // Draw major numerals around dial
                if (isMajor) {
                    val numRadius = tickInnerR - 11.dp.toPx()
                    val nx = (center.x + numRadius * cos(tickRad)).toFloat()
                    val ny = (center.y + numRadius * sin(tickRad)).toFloat()

                    drawIntoCanvas { canvas ->
                        val fm = textPaint.fontMetrics
                        val yOffset = (fm.descent + fm.ascent) / 2f
                        canvas.nativeCanvas.drawText(
                            currentTickVal.toString(),
                            nx,
                            ny - yOffset,
                            textPaint
                        )
                    }
                }
            }

            // 6. AERODYNAMIC NEEDLE (Tapered Polygon with Accent Tip)
            val needleAngleDeg = 135.0 + (270.0 * fraction)
            val needleRad = Math.toRadians(needleAngleDeg)
            val perpRad = needleRad + Math.PI / 2.0

            val needleLength = outerRadius - (strokeWidth / 2f) - 6.dp.toPx()
            val needleBaseWidth = 6.dp.toPx()
            val counterWeight = 20.dp.toPx()

            val tip = Offset(
                (center.x + needleLength * cos(needleRad)).toFloat(),
                (center.y + needleLength * sin(needleRad)).toFloat()
            )
            val baseLeft = Offset(
                (center.x - counterWeight * 0.5f * cos(needleRad) + needleBaseWidth * cos(perpRad)).toFloat(),
                (center.y - counterWeight * 0.5f * sin(needleRad) + needleBaseWidth * sin(perpRad)).toFloat()
            )
            val baseRight = Offset(
                (center.x - counterWeight * 0.5f * cos(needleRad) - needleBaseWidth * cos(perpRad)).toFloat(),
                (center.y - counterWeight * 0.5f * sin(needleRad) - needleBaseWidth * sin(perpRad)).toFloat()
            )
            val counterTip = Offset(
                (center.x - counterWeight * cos(needleRad)).toFloat(),
                (center.y - counterWeight * sin(needleRad)).toFloat()
            )

            val needlePath = Path().apply {
                moveTo(tip.x, tip.y)
                lineTo(baseRight.x, baseRight.y)
                lineTo(counterTip.x, counterTip.y)
                lineTo(baseLeft.x, baseLeft.y)
                close()
            }

            // Needle shadow / glow
            drawPath(
                path = needlePath,
                color = if (isOverSpeed) Color(0xFFEF4444) else activeArcColor
            )

            // Needle spine line
            drawLine(
                color = Color.White.copy(alpha = 0.85f),
                start = center,
                end = tip,
                strokeWidth = 1.5.dp.toPx(),
                cap = StrokeCap.Round
            )

            // 7. CENTER METALLIC HUB CAP
            drawCircle(color = Color(0xFF1E293B), radius = 22.dp.toPx(), center = center)
            drawCircle(
                color = Color(0xFF475569),
                radius = 22.dp.toPx(),
                center = center,
                style = Stroke(width = 2.dp.toPx())
            )
            drawCircle(color = Color(0xFF0F172A), radius = 14.dp.toPx(), center = center)
            drawCircle(
                color = if (isOverSpeed) Color(0xFFEF4444) else theme.primary,
                radius = 5.dp.toPx(),
                center = center
            )
        }

        // CENTER DIGITAL SPEED READOUT & UNIT BADGE
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(top = 18.dp)
        ) {
            Text(
                text = rawSpeed.roundToInt().toString(),
                fontSize = 74.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace,
                color = if (isOverSpeed) Color(0xFFEF4444) else Color.White,
                letterSpacing = (-2).sp
            )

            Row(
                modifier = Modifier
                    .background(
                        if (isOverSpeed) Color(0xFFEF4444).copy(alpha = 0.2f) else theme.primary.copy(alpha = 0.15f),
                        RoundedCornerShape(8.dp)
                    )
                    .border(
                        1.dp,
                        if (isOverSpeed) Color(0xFFEF4444) else theme.primary.copy(alpha = 0.5f),
                        RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 14.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = unitLabel,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (isOverSpeed) Color(0xFFEF4444) else theme.primary,
                    letterSpacing = 1.sp
                )
            }

            if (speedLimit > 0f) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "BATAS: ${speedLimit.roundToInt()} $unitLabel",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isOverSpeed) Color(0xFFEF4444) else Color(0xFF94A3B8)
                )
            }
        }
    }
}

// ==========================================
// TELEMETRY METRICS GRID (6 TILES)
// ==========================================
@Composable
fun TelemetryGrid(
    maxSpeed: String,
    avgSpeed: String,
    distance: String,
    tripTime: String,
    altitude: String,
    heading: String,
    accuracy: String,
    coords: String,
    theme: CockpitTheme
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            StatCard(
                title = "KECEPATAN MAKS",
                value = maxSpeed,
                accentColor = Color(0xFFF43F5E),
                modifier = Modifier.weight(1f)
            )
            StatCard(
                title = "KECEPATAN RATA2",
                value = avgSpeed,
                accentColor = theme.accent,
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            StatCard(
                title = "JARAK TEMPUH",
                value = distance,
                accentColor = theme.primary,
                modifier = Modifier.weight(1f)
            )
            StatCard(
                title = "WAKTU TEMPUH",
                value = tripTime,
                accentColor = Color(0xFF38BDF8),
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            StatCard(
                title = "KETINGGIAN (ASL)",
                value = altitude,
                accentColor = Color(0xFFA855F7),
                modifier = Modifier.weight(1f)
            )
            StatCard(
                title = "ARAH KOMPAS",
                value = heading,
                accentColor = Color(0xFFEAB308),
                modifier = Modifier.weight(1f)
            )
        }

        // SATELLITE ACCURACY & GPS LOCATION BANNER
        Surface(
            color = Color(0xFF101726),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(12.dp))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("AKURASI SATELIT", color = Color(0xFF64748B), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text(accuracy, color = Color(0xFFCBD5E1), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("KOORDINAT GPS", color = Color(0xFF64748B), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text(coords, color = Color(0xFFCBD5E1), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable
fun StatCard(
    title: String,
    value: String,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        color = Color(0xFF101726),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier.border(1.dp, Color(0xFF1E293B), RoundedCornerShape(14.dp))
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(accentColor)
                )
                Text(
                    text = title,
                    color = Color(0xFF64748B),
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                color = Color.White,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                maxLines = 1
            )
        }
    }
}

// ==========================================
// BOTTOM CONTROL ACTION BAR
// ==========================================
@Composable
fun ControlActionBar(
    isHudMirror: Boolean,
    isTripPaused: Boolean,
    activeLimitKmh: Float,
    theme: CockpitTheme,
    onToggleHud: () -> Unit,
    onTogglePause: () -> Unit,
    onOpenSpeedLimit: () -> Unit,
    onResetClick: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // HUD MODE BUTTON
            Button(
                onClick = onToggleHud,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isHudMirror) Color(0xFF0284C7) else Color(0xFF1E293B),
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = if (isHudMirror) "Exit HUD" else "Mode HUD Kaca",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // SPEED LIMIT BUTTON
            Button(
                onClick = onOpenSpeedLimit,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (activeLimitKmh > 0f) Color(0xFFB45309) else Color(0xFF1E293B),
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = if (activeLimitKmh > 0f) "Batas: ${activeLimitKmh.roundToInt()} km/h" else "Batas Speed",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // PAUSE / RESUME TRIP BUTTON
            Button(
                onClick = onTogglePause,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isTripPaused) Color(0xFF047857) else Color(0xFF334155),
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = if (isTripPaused) "▶ Lanjutkan Trip" else "⏸ Jeda Trip",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // RESET TRIP BUTTON
            OutlinedButton(
                onClick = onResetClick,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF4444)),
                border = ButtonDefaults.outlinedButtonBorder.copy(brush = Brush.linearGradient(listOf(Color(0xFFEF4444), Color(0xFFEF4444)))),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text("Reset Trip", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ==========================================
// SPEED LIMIT DIALOG
// ==========================================
@Composable
fun SpeedLimitDialog(
    currentLimitKmh: Float,
    unit: SpeedUnit,
    onSelectLimit: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    val presets = listOf(0f, 40f, 60f, 80f, 100f, 120f)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Atur Peringatan Kecepatan") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Aplikasi akan memberi peringatan getar dan warna merah ketika kecepatan melebihi batas yang dipilih:",
                    fontSize = 13.sp,
                    color = Color(0xFF94A3B8)
                )
                Spacer(modifier = Modifier.height(4.dp))
                presets.forEach { kmh ->
                    val isSelected = (currentLimitKmh == kmh)
                    val label = if (kmh == 0f) "Nonaktifkan (OFF)" else "${kmh.roundToInt()} km/h (${(kmh * (unit.speedMultiplier / 3.6f)).roundToInt()} ${unit.label.uppercase()})"
                    Surface(
                        color = if (isSelected) Color(0xFF10B981).copy(alpha = 0.2f) else Color(0xFF1E293B),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectLimit(kmh) }
                            .border(
                                1.dp,
                                if (isSelected) Color(0xFF10B981) else Color.Transparent,
                                RoundedCornerShape(10.dp)
                            )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(label, color = if (isSelected) Color(0xFF10B981) else Color.White, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                            if (isSelected) Text("✓", color = Color(0xFF10B981), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Tutup") }
        }
    )
}

// ==========================================
// UTILITY FUNCTIONS
// ==========================================
fun formatDuration(totalSeconds: Long): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format("%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}

fun getDirectionLabel(bearing: Float): String {
    val normalized = (bearing % 360 + 360) % 360
    val directions = arrayOf("UTARA", "TIMUR LAUT", "TIMUR", "TENGGARA", "SELATAN", "BARAT DAYA", "BARAT", "BARAT LAUT")
    val index = (((normalized + 22.5f) % 360) / 45).toInt().coerceIn(0, 7)
    return "${directions[index]} (${normalized.roundToInt()}°)"
}
