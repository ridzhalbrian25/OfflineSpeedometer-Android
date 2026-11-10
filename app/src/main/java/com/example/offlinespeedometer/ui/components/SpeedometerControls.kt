package com.example.offlinespeedometer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.offlinespeedometer.model.NavigationTab
import com.example.offlinespeedometer.model.SpeedUnit
import com.example.offlinespeedometer.util.formatTimerDuration
import kotlin.math.roundToInt

// ==========================================
// 1. TOP HEADER & GPS ANTENNA BARS
// ==========================================
@Composable
fun TopHeader(
    gpsLevel: Int,
    accuracyMeters: Float,
    onOpenDrawer: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
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

@Composable
fun GpsSignalStrengthBars(
    level: Int,
    accuracyMeters: Float,
    modifier: Modifier = Modifier
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
        modifier = modifier
            .padding(start = 2.dp)
            .height(16.dp)
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(if (level >= 1) barColor else Color(0xFF333842))
        )
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(7.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(if (level >= 2) barColor else Color(0xFF333842))
        )
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(10.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(if (level >= 3) barColor else Color(0xFF333842))
        )
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
    onToggleHud: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
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

        // HUD FLIP BUTTON
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
// 3. TELEMETRY PANEL (3 COLUMNS)
// ==========================================
@Composable
fun ReferenceTelemetryPanel(
    maxSpeed: String,
    distance: String,
    avgSpeed: String,
    speedUnit: String,
    distanceUnit: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
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
// 4. PRIMARY ACTION BUTTON
// ==========================================
@Composable
fun PrimaryActionButton(
    isRunning: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFF00E5FF),
            contentColor = Color(0xFF000000)
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
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
// 5. BOTTOM NAVIGATION BAR
// ==========================================
@Composable
fun BottomNavigationBar(
    selectedTab: NavigationTab,
    onTabSelected: (NavigationTab) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = Color(0xFF000000),
        modifier = modifier.fillMaxWidth()
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
// 6. PERMISSION CARD
// ==========================================
@Composable
fun GpsPermissionCard(
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = Color(0xFF191B1F),
        shape = RoundedCornerShape(10.dp),
        modifier = modifier
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
// 7. SETTINGS DIALOG (UNITS & SPEED LIMIT)
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
