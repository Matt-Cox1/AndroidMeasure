package dev.precisionmeasure.app.ui

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.precisionmeasure.app.measurement.DevTelemetry
import dev.precisionmeasure.app.measurement.MeasurementUiState
import dev.precisionmeasure.app.measurement.SnapTargetKind
import dev.precisionmeasure.app.settings.ReticleStyle
import dev.precisionmeasure.geometry.Vec2

@Composable
internal fun ReadoutGlassCard(uiState: MeasurementUiState) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xD810151E))
            .border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(18.dp))
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val primary = uiState.primaryReadout
            if (primary != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = primary,
                        color = Color.White,
                        fontSize = 25.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                    )
                    uiState.accuracyTierLabel?.let { tier ->
                        val color = when (tier) {
                            "High Accuracy", "Saved" -> Color(0xFF00E676)
                            "Good" -> Color(0xFF38BDF8)
                            else -> Color(0xFFFFAB00)
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(color.copy(alpha = 0.18f))
                                .border(1.dp, color.copy(alpha = 0.8f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        ) {
                            Text(tier, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                uiState.secondaryReadout?.let { sec ->
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(sec, color = Color(0xFF94A3B8), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val statusDotColor = when {
                        !uiState.isArTracking -> Color(0xFFFF5252)
                        uiState.overlay.surfaceLocked -> Color(0xFF00E676)
                        else -> Color(0xFFFFAB00)
                    }
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(statusDotColor),
                    )
                    Text(
                        text = uiState.statusMessage,
                        color = Color(0xFFE2E8F0),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

@Composable
internal fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String,
    highlighted: Boolean = false,
    badgeCount: Int = 0,
    onClick: () -> Unit,
) {
    Box(contentAlignment = Alignment.TopEnd) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(if (highlighted) Color(0xDD00E5FF) else Color(0xAA0F141D))
                .border(1.dp, Color(0x28FFFFFF), CircleShape)
                .clickable { onClick() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (highlighted) Color.Black else Color.White,
                modifier = Modifier.size(20.dp),
            )
        }
        if (badgeCount > 0) {
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color(0xFF00E5FF))
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            ) {
                Text(
                    text = if (badgeCount > 99) "99+" else "$badgeCount",
                    color = Color.Black,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
internal fun GlassTextPill(
    text: String,
    accent: Color = Color.White,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .height(38.dp)
            .clip(RoundedCornerShape(19.dp))
            .background(Color(0xAA0F141D))
            .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(19.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = accent,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
internal fun RoundActionButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(enabled = enabled) { onClick() },
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(Color(0xCC161E2B))
                .border(1.dp, if (enabled) Color(0x55FFFFFF) else Color(0x22FFFFFF), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (enabled) Color.White else Color(0x44FFFFFF),
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            color = if (enabled) Color(0xFFCBD5E1) else Color(0x44FFFFFF),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
internal fun LoupePreviewBox(
    bitmap: Bitmap,
    snapOffsetNorm: Vec2?,
    zoomLabel: String,
) {
    Box(
        modifier = Modifier
            .size(92.dp)
            .clip(CircleShape)
            .background(Color(0xDD111111))
            .border(2.dp, Color.White.copy(alpha = 0.85f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Loupe",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Canvas(modifier = Modifier.fillMaxSize()) {
            val c = Offset(size.width * 0.5f, size.height * 0.5f)
            drawLine(Color(0xCCFF5252), Offset(c.x - 12f, c.y), Offset(c.x + 12f, c.y), 2f)
            drawLine(Color(0xCCFF5252), Offset(c.x, c.y - 12f), Offset(c.x, c.y + 12f), 2f)
            if (snapOffsetNorm != null) {
                val sx = (0.5f + snapOffsetNorm.x.toFloat()) * size.width
                val sy = (0.5f + snapOffsetNorm.y.toFloat()) * size.height
                drawCircle(Color(0xFF00E676), 5.5f, Offset(sx, sy), style = Stroke(2f))
            }
        }
        Text(
            text = zoomLabel,
            color = Color.White,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xAA000000))
                .padding(horizontal = 5.dp, vertical = 1.dp),
        )
    }
}

@Composable
internal fun DepthLegendCard(label: String) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xCC0F141D))
            .padding(horizontal = 9.dp, vertical = 6.dp),
    ) {
        Text(
            text = "${label.uppercase()} (0.15m -> 2.5m)",
            color = Color.White,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(modifier = Modifier.height(3.dp))
        Box(
            modifier = Modifier
                .width(142.dp)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            Color(0xFFFF3D00),
                            Color(0xFFFFEA00),
                            Color(0xFF00E676),
                            Color(0xFF00B0FF),
                            Color(0xFF304FFE),
                        ),
                    ),
                ),
        )
    }
}

@Composable
internal fun DevTelemetryCard(t: DevTelemetry) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xCC0D1117))
            .border(1.dp, Color(0x66BA68C8), RoundedCornerShape(10.dp))
            .padding(horizontal = 9.dp, vertical = 6.dp),
    ) {
        Text("FPS ${"%.1f".format(t.fps)} | f=${"%.0f".format(t.focalFx)},${"%.0f".format(t.focalFy)}", color = Color(0xFFE1BEE7), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        Text("CPU ${t.cpuImageSize} | DEP ${t.depthImageSize}", color = Color(0xFFB0BEC5), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        Text("CTR ${t.centerDepthMm} mm (conf ${t.centerRawConfidence}/255)", color = Color(0xFF80DEEA), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        Text("SLAM ${t.slamFeatureCount} | D-PTS ${t.depthCloudCount} | PLN ${t.planeCount}", color = Color(0xFFA5D6A7), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        Text("CAM (${"%.2f".format(t.cameraPosMeters.x)}, ${"%.2f".format(t.cameraPosMeters.y)}, ${"%.2f".format(t.cameraPosMeters.z)})", color = Color(0xFF90A4AE), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
    }
}

internal fun DrawScope.drawReticle(
    style: ReticleStyle,
    surfaceLocked: Boolean,
    snapKind: SnapTargetKind,
) {
    val cx = size.width * 0.5f
    val cy = size.height * 0.5f
    val center = Offset(cx, cy)
    val dotColor = when {
        snapKind == SnapTargetKind.CLOSE_LOOP -> Color(0xFF00E676)
        snapKind == SnapTargetKind.EXISTING_PIN -> Color(0xFFFFD600)
        surfaceLocked -> Color(0xFF00E676)
        else -> Color(0xFFFF5252)
    }

    when (style) {
        ReticleStyle.CROSSHAIR -> {
            val arm = 22f
            val gap = 7f
            drawLine(Color(0xDDFFFFFF), Offset(cx - arm, cy), Offset(cx - gap, cy), 2.2f)
            drawLine(Color(0xDDFFFFFF), Offset(cx + gap, cy), Offset(cx + arm, cy), 2.2f)
            drawLine(Color(0xDDFFFFFF), Offset(cx, cy - arm), Offset(cx, cy - gap), 2.2f)
            drawLine(Color(0xDDFFFFFF), Offset(cx, cy + gap), Offset(cx, cy + arm), 2.2f)
            drawCircle(dotColor, 4.5f, center)
        }
        ReticleStyle.DOT -> {
            drawCircle(Color(0x88000000), 11f, center)
            drawCircle(Color.White, 9f, center, style = Stroke(2f))
            drawCircle(dotColor, 4.5f, center)
        }
        ReticleStyle.CALIPER -> {
            drawCircle(Color(0xCCFFFFFF), 20f, center, style = Stroke(2f))
            drawCircle(dotColor, 4.5f, center)
        }
    }
}

internal fun DrawScope.drawFloatingPill(
    center: Offset,
    line1: String,
    line2: String?,
    borderColor: Int,
    bgColor: Int,
    labelPaint: Paint,
    subPaint: Paint,
    bgPaint: Paint,
    borderPaint: Paint,
) {
    val w1 = labelPaint.measureText(line1)
    val w2 = if (line2 != null) subPaint.measureText(line2) else 0f
    val padX = 20f
    val pillW = maxOf(w1, w2) + padX * 2f
    val pillH = if (line2 != null) 78f else 50f
    val rect = RectF(
        center.x - pillW * 0.5f,
        center.y - pillH * 0.5f,
        center.x + pillW * 0.5f,
        center.y + pillH * 0.5f,
    )

    bgPaint.color = bgColor
    borderPaint.color = borderColor
    val canvas = drawContext.canvas.nativeCanvas
    canvas.drawRoundRect(rect, 16f, 16f, bgPaint)
    canvas.drawRoundRect(rect, 16f, 16f, borderPaint)

    if (line2 != null) {
        canvas.drawText(line1, center.x, center.y - 4f, labelPaint)
        canvas.drawText(line2, center.x, center.y + 24f, subPaint)
    } else {
        canvas.drawText(line1, center.x, center.y + 11f, labelPaint)
    }
}
