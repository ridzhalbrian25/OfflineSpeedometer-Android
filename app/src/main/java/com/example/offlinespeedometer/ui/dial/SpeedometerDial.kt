package com.example.offlinespeedometer.ui.dial

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 1:1 Reference Analog Dial Canvas Engine.
 * Features 270-degree horseshoe arc, neon cyan glow, calibrated ticks,
 * aerodynamic white needle with circular hub, and embedded digital speed readout.
 */
@Composable
fun ReferenceSpeedometerDial(
    speed: Float,
    rawSpeed: Float,
    unitText: String,
    maxGauge: Int,
    isOverSpeed: Boolean,
    modifier: Modifier = Modifier
) {
    val fraction = (speed / maxGauge).coerceIn(0f, 1f)

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
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

            // 1. Inactive Track Arc (#525861)
            drawArc(
                color = Color(0xFF525861),
                startAngle = 135f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = arcTopLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )

            // 2. Active Track Arc (#00E5FF Neon Cyan)
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

                // 3. Glowing Lead Indicator Dot
                val leadAngleDeg = 135.0 + (270.0 * fraction)
                val leadRad = Math.toRadians(leadAngleDeg)
                val leadCenter = Offset(
                    (center.x + outerRadius * cos(leadRad)).toFloat(),
                    (center.y + outerRadius * sin(leadRad)).toFloat()
                )

                drawCircle(
                    color = (if (isOverSpeed) Color(0xFFEF4444) else Color(0xFF00E5FF)).copy(alpha = 0.45f),
                    radius = strokeWidth * 0.9f,
                    center = leadCenter
                )
                drawCircle(
                    color = Color(0xFFE0FFFF),
                    radius = strokeWidth * 0.35f,
                    center = leadCenter
                )
            }

            // 4. Radial Ticks and Cyan Numbers (0, 20, 40, ... 220)
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

                // Minor tick
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

            // 5. Sharp Tapered White Needle
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

            // 6. Center Hub Pivot
            drawCircle(color = Color.White, radius = 12.dp.toPx(), center = center)
            drawCircle(color = Color(0xFF15171B), radius = 7.dp.toPx(), center = center)
        }

        // 7. Digital Speed Readout Inside Opening
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

/**
 * Fullscreen Digital Speedometer Display Mode
 */
@Composable
fun DigitalSpeedDisplay(
    rawSpeed: Float,
    unitText: String,
    isOverSpeed: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
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

/**
 * Detailed GPS Metrics & Geographical Coordinates View
 */
@Composable
fun GpsMetricsDisplay(
    altitudeMeters: Double,
    accuracyMeters: Float,
    bearingDegrees: Float,
    hasBearing: Boolean,
    latitude: Double,
    longitude: Double,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
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
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Altitude", color = Color(0xFF9EA5AF), fontSize = 14.sp)
                    Text(
                        String.format(Locale.US, "%.0f m ASL", altitudeMeters),
                        color = Color(0xFF00E5FF),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Heading", color = Color(0xFF9EA5AF), fontSize = 14.sp)
                    Text(
                        if (hasBearing) String.format(Locale.US, "%.0f°", bearingDegrees) else "—",
                        color = Color(0xFF00E5FF),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("GPS Precision", color = Color(0xFF9EA5AF), fontSize = 14.sp)
                    Text(
                        if (accuracyMeters > 0f) String.format(Locale.US, "±%.0f m", accuracyMeters) else "Searching...",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Coordinates", color = Color(0xFF9EA5AF), fontSize = 14.sp)
                    Text(
                        String.format(Locale.US, "%.4f°, %.4f°", latitude, longitude),
                        color = Color(0xFFCBD5E1),
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}
