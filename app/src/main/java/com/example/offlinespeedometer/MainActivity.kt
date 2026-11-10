package com.example.offlinespeedometer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
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
    val maxGaugeValue: Int
) {
    METRIC("km/h", "km", "m", 3.6f, 0.001, 1.0, 240),
    IMPERIAL("mph", "mi", "ft", 2.236936f, 0.000621371, 3.28084, 160)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF090D16)
                ) {
                    OfflineSpeedometerScreen()
                }
            }
        }
    }
}

@Composable
fun OfflineSpeedometerScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("speedometer_prefs", Context.MODE_PRIVATE) }

    var unit by remember {
        val saved = prefs.getString("unit", SpeedUnit.METRIC.name) ?: SpeedUnit.METRIC.name
        mutableStateOf(SpeedUnit.valueOf(saved))
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
    var isHudMirror by remember { mutableStateOf(false) }
    var lastLocation by remember { mutableStateOf<Location?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasLocationPermission = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
    }

    DisposableEffect(hasLocationPermission) {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val measuredMps = if (location.hasSpeed()) {
                    location.speed
                } else {
                    lastLocation?.let { prev ->
                        val dt = (location.time - prev.time) / 1000.0f
                        if (dt > 0f) prev.distanceTo(location) / dt else 0f
                    } ?: 0f
                }

                val cleanMps = if (measuredMps < 0.45f) 0f else measuredMps
                currentSpeedMps = cleanMps
                if (cleanMps > maxSpeedMps) maxSpeedMps = cleanMps

                lastLocation?.let { prev ->
                    val stepMeters = prev.distanceTo(location).toDouble()
                    if (stepMeters in 0.5..500.0) {
                        totalDistanceMeters += stepMeters
                    }
                }
                lastLocation = location
                if (location.hasAltitude()) altitudeMeters = location.altitude
                if (location.hasAccuracy()) accuracyMeters = location.accuracy
            }

            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }

        if (hasLocationPermission) {
            try {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    500L,
                    0f,
                    listener
                )
            } catch (_: SecurityException) {}
        }

        onDispose {
            locationManager.removeUpdates(listener)
        }
    }

    val displaySpeed = currentSpeedMps * unit.speedMultiplier
    val animatedSpeed by animateFloatAsState(
        targetValue = displaySpeed,
        animationSpec = tween(durationMillis = 350),
        label = "speedAnimation"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { scaleX = if (isHudMirror) -1f else 1f }
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "GPS Speedometer Offline",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )

            Row(
                modifier = Modifier
                    .background(Color(0xFF1E293B), RoundedCornerShape(12.dp))
                    .padding(4.dp)
            ) {
                SpeedUnit.entries.forEach { item ->
                    val selected = unit == item
                    Button(
                        onClick = {
                            unit = item
                            prefs.edit().putString("unit", item.name).apply()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (selected) Color(0xFF10B981) else Color.Transparent,
                            contentColor = if (selected) Color(0xFF090D16) else Color.LightGray
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(item.label.uppercase(), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
        }

        if (!hasLocationPermission) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "Izin lokasi GPS diperlukan untuk membaca kecepatan satelit secara offline.",
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(onClick = {
                        permissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                            )
                        )
                    }) {
                        Text("Aktifkan Sensor GPS Offline")
                    }
                }
            }
        }

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(300.dp)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val strokeWidth = 22.dp.toPx()
                val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
                val topLeft = Offset(strokeWidth / 2, strokeWidth / 2)

                drawArc(
                    color = Color(0xFF1E293B),
                    startAngle = 135f,
                    sweepAngle = 270f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )

                val fraction = (animatedSpeed / unit.maxGaugeValue).coerceIn(0f, 1f)
                drawArc(
                    color = if (fraction > 0.75f) Color(0xFFEF4444) else Color(0xFF10B981),
                    startAngle = 135f,
                    sweepAngle = 270f * fraction,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )

                val angleDeg = 135.0 + (270.0 * fraction)
                val angleRad = Math.toRadians(angleDeg)
                val center = Offset(size.width / 2, size.height / 2)
                val needleLength = (size.width / 2) - strokeWidth * 1.4f
                val needleEnd = Offset(
                    x = (center.x + needleLength * cos(angleRad)).toFloat(),
                    y = (center.y + needleLength * sin(angleRad)).toFloat()
                )
                drawLine(
                    color = Color(0xFFF43F5E),
                    start = center,
                    end = needleEnd,
                    strokeWidth = 8.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = animatedSpeed.roundToInt().toString(),
                    fontSize = 76.sp,
                    fontWeight = FontWeight.ExtraBold,
                    fontFamily = FontFamily.Monospace,
                    color = Color.White
                )
                Text(
                    text = unit.label.uppercase(),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF10B981)
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatCard(
                    title = "KECEPATAN MAKS",
                    value = String.format("%.1f %s", maxSpeedMps * unit.speedMultiplier, unit.label),
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    title = "JARAK TEMPUH",
                    value = String.format("%.2f %s", totalDistanceMeters * unit.distanceMultiplier, unit.distanceLabel),
                    modifier = Modifier.weight(1f)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatCard(
                    title = "KETINGGIAN",
                    value = String.format("%.0f %s", altitudeMeters * unit.altitudeMultiplier, unit.altitudeLabel),
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    title = "AKURASI SATELIT",
                    value = if (accuracyMeters > 0) String.format("±%.0f %s", accuracyMeters * unit.altitudeMultiplier, unit.altitudeLabel) else "Mencari GPS...",
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = { isHudMirror = !isHudMirror },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (isHudMirror) "Mode Normal" else "Mode HUD Kaca")
                }
                Button(
                    onClick = {
                        maxSpeedMps = 0f
                        totalDistanceMeters = 0.0
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155)),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Reset Trip", color = Color.White)
                }
            }
        }
    }
}

@Composable
fun StatCard(title: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        color = Color(0xFF131C2E),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(text = title, color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = value, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        }
    }
}
