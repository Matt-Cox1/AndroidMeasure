package dev.precisionmeasure.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.precisionmeasure.app.measurement.DepthMapOverlayMode
import dev.precisionmeasure.app.measurement.DevOverlayOptions
import dev.precisionmeasure.geometry.ReferenceObject

enum class FeedbackCategory(val label: String) {
    BUG("Bug / Issue"),
    ACCURACY("Accuracy"),
    FEATURE("Feature Idea"),
    OTHER("General"),
}

/**
 * Strictly text-only feedback form. Sends only what the user types via their own email app
 * (mailto:) or copies to clipboard. Zero screenshots, zero logs, zero telemetry.
 */
@Composable
fun FeedbackDialog(
    onSendEmail: (category: FeedbackCategory, description: String) -> Unit,
    onCopyText: (category: FeedbackCategory, description: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var category by remember { mutableStateOf(FeedbackCategory.BUG) }
    var description by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Send Feedback", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Please describe your issue or idea in as much detail as possible: what you were measuring, which mode you used, and what happened.",
                    fontSize = 13.sp,
                    color = Color(0xFFCBD5E1),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FeedbackCategory.entries.forEach { cat ->
                        val selected = category == cat
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (selected) Color(0xFF00E5FF) else Color(0xFF1E2634))
                                .clickable { category = cat }
                                .padding(vertical = 7.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = cat.label.substringBefore(" "),
                                color = if (selected) Color.Black else Color(0xFFE2E8F0),
                                fontSize = 11.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Detailed description") },
                    placeholder = {
                        Text(
                            "Example: In Perimeter mode on a wooden table, tapping the 4th corner...",
                            fontSize = 12.sp,
                        )
                    },
                    minLines = 5,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0x2200E676))
                        .border(1.dp, Color(0x5500E676), RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = "Privacy promise: Text only. No screenshots, logs, device IDs, or telemetry are ever collected or attached.",
                        color = Color(0xFFA7F3D0),
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = description.trim().length >= 8,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF00E5FF),
                    contentColor = Color.Black,
                ),
                onClick = {
                    onSendEmail(category, description.trim())
                    onDismiss()
                },
            ) {
                Text("Send Email", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            Row {
                if (description.trim().isNotEmpty()) {
                    TextButton(
                        onClick = {
                            onCopyText(category, description.trim())
                            onDismiss()
                        },
                    ) {
                        Text("Copy")
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        },
    )
}

@Composable
internal fun QuickDevDialog(
    options: DevOverlayOptions,
    onUpdate: ((DevOverlayOptions) -> DevOverlayOptions) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Labs & Developer Overlays") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Live Depth Heatmap", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text("Turbo false-color depth map", fontSize = 11.sp, color = Color(0xFFB0BEC5))
                    }
                    GlassTextPill(
                        text = options.depthMapMode.label.uppercase(),
                        accent = if (options.depthMapMode == DepthMapOverlayMode.OFF) Color.White else Color(0xFFCE93D8),
                        onClick = { onUpdate { it.copy(depthMapMode = it.depthMapMode.next()) } },
                    )
                }
                QuickDevSwitch("3D Point Cloud", options.showPointCloud) { v -> onUpdate { it.copy(showPointCloud = v) } }
                QuickDevSwitch("Detected 3D Planes", options.showPlanes) { v -> onUpdate { it.copy(showPlanes = v) } }
                QuickDevSwitch("Multi-View 3D Rays", options.showTriangulationRays) { v -> onUpdate { it.copy(showTriangulationRays = v) } }
                QuickDevSwitch("Sensor Telemetry HUD", options.showTelemetryHud) { v -> onUpdate { it.copy(showTelemetryHud = v) } }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = {
            if (options.anyActive) {
                TextButton(onClick = { onUpdate { DevOverlayOptions() } }) { Text("All Off") }
            }
        },
    )
}

@Composable
private fun QuickDevSwitch(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
internal fun CalibrationDialog(
    currentCalibrationLabel: String,
    isCalibrated: Boolean,
    hasValidMeasurement: Boolean,
    onApply: (trueLengthMeters: Double, label: String) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedRef by remember { mutableStateOf(ReferenceObject.CREDIT_CARD) }
    var customMmText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Metric Scale Calibration") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Measure a known reference line (like a credit card), then pick its true length below to remove ARCore scale drift.",
                    fontSize = 13.sp,
                )
                Text(
                    text = "Current: $currentCalibrationLabel",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )

                ReferenceObject.entries.forEach { ref ->
                    val isSelected = selectedRef == ref && customMmText.isBlank()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) Color(0x3300E5FF) else Color.Transparent)
                            .clickable {
                                selectedRef = ref
                                customMmText = ""
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(text = ref.displayName, fontSize = 13.sp)
                        Text(
                            text = "${"%.2f".format(ref.lengthMeters * 1000.0)} mm",
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))
                OutlinedTextField(
                    value = customMmText,
                    onValueChange = { customMmText = it },
                    label = { Text("Or custom true length (mm)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                enabled = hasValidMeasurement,
                onClick = {
                    val customMm = customMmText.toDoubleOrNull()
                    if (customMm != null && customMm >= 10.0) {
                        onApply(customMm * 1e-3, "Custom (${"%.1f".format(customMm)} mm)")
                    } else {
                        onApply(selectedRef.lengthMeters, selectedRef.displayName)
                    }
                },
            ) {
                Text("Apply Scale")
            }
        },
        dismissButton = {
            Row {
                if (isCalibrated) {
                    TextButton(onClick = onReset) { Text("Reset") }
                }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
    )
}
