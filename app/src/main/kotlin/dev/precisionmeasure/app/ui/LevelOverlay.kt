package dev.precisionmeasure.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.precisionmeasure.app.sensor.LevelReading
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun LevelOverlay(
    reading: LevelReading,
    hapticsEnabled: Boolean,
    onTare: () -> Unit,
    onClearTare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    var wasLevel by remember { mutableStateOf(false) }

    LaunchedEffect(reading.isLevel) {
        if (reading.isLevel && !wasLevel && hapticsEnabled) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
        wasLevel = reading.isLevel
    }

    val accent = if (reading.isLevel) Color(0xFF00E676) else Color(0xFF00E5FF)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xD80A0D12)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(bottom = 110.dp),
        ) {
            Text(
                text = if (reading.isFlatOrientation) "SURFACE LEVEL" else "EDGE / WALL LEVEL",
                color = Color(0xFF94A3B8),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.4.sp,
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "${"%.1f".format(reading.totalTiltDeg)}°",
                color = accent,
                fontSize = 54.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                text = if (reading.isLevel) "LEVEL" else "Pitch ${"%.1f".format(reading.pitchDeg)}°  ·  Roll ${"%.1f".format(reading.rollDeg)}°",
                color = if (reading.isLevel) Color(0xFF00E676) else Color(0xFFCBD5E1),
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
            )

            Spacer(modifier = Modifier.height(28.dp))

            Canvas(modifier = Modifier.size(230.dp)) {
                val center = Offset(size.width * 0.5f, size.height * 0.5f)
                val outerR = size.minDimension * 0.46f

                drawCircle(
                    color = Color(0xFF161B24),
                    radius = outerR,
                    center = center,
                )
                drawCircle(
                    color = accent.copy(alpha = if (reading.isLevel) 0.9f else 0.35f),
                    radius = outerR,
                    center = center,
                    style = Stroke(width = 3.5f),
                )
                drawCircle(
                    color = Color.White.copy(alpha = 0.14f),
                    radius = outerR * 0.5f,
                    center = center,
                    style = Stroke(width = 1.5f),
                )

                // Crosshairs
                drawLine(Color.White.copy(alpha = 0.16f), Offset(center.x - outerR, center.y), Offset(center.x + outerR, center.y), 1.5f)
                drawLine(Color.White.copy(alpha = 0.16f), Offset(center.x, center.y - outerR), Offset(center.x, center.y + outerR), 1.5f)

                if (reading.isFlatOrientation) {
                    // Bull's-eye bubble
                    val targetR = 26f
                    drawCircle(Color.White.copy(alpha = 0.45f), targetR, center, style = Stroke(2f))
                    val maxTilt = 20.0
                    val dx = ((reading.rollDeg / maxTilt).coerceIn(-1.0, 1.0) * (outerR - 22f)).toFloat()
                    val dy = ((-reading.pitchDeg / maxTilt).coerceIn(-1.0, 1.0) * (outerR - 22f)).toFloat()
                    val bubblePos = Offset(center.x + dx, center.y + dy)
                    drawCircle(accent.copy(alpha = 0.28f), 30f, bubblePos)
                    drawCircle(accent, 18f, bubblePos)
                } else {
                    // Rotating horizon bar
                    val rad = (-reading.rollDeg * PI / 180.0)
                    val dx = (cos(rad) * (outerR - 14f)).toFloat()
                    val dy = (sin(rad) * (outerR - 14f)).toFloat()
                    drawLine(
                        color = accent,
                        start = Offset(center.x - dx, center.y - dy),
                        end = Offset(center.x + dx, center.y + dy),
                        strokeWidth = 8f,
                    )
                    drawCircle(accent, 10f, center)
                }
            }

            Spacer(modifier = Modifier.height(22.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xFF1E293B))
                        .border(1.dp, accent.copy(alpha = 0.55f), RoundedCornerShape(20.dp))
                        .clickable { onTare() }
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = if (reading.hasTareOffset) "Re-Zero Relative" else "Zero / Tare Here",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
                if (reading.hasTareOffset) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color(0xFF1E293B))
                            .border(1.dp, Color(0x66FFFFFF), RoundedCornerShape(20.dp))
                            .clickable { onClearTare() }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Text(
                            text = "Reset True",
                            color = Color(0xFFCBD5E1),
                            fontSize = 13.sp,
                        )
                    }
                }
            }
        }
    }
}
