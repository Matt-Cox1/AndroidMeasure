package dev.precisionmeasure.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.SquareFoot
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class OnboardingStep(
    val icon: ImageVector,
    val title: String,
    val body: String,
)

@Composable
fun OnboardingOverlay(onFinish: () -> Unit) {
    val steps = remember {
        listOf(
            OnboardingStep(
                icon = Icons.Rounded.Straighten,
                title = "Aim and Tap +",
                body = "Move your phone gently until the surface ring locks green, aim at your first corner, and tap +. Move slightly sideways after placing both ends for highest 3D precision.",
            ),
            OnboardingStep(
                icon = Icons.Rounded.SquareFoot,
                title = "Lines, Perimeters, Boxes & More",
                body = "Switch modes along the bottom bar to measure closed perimeters and areas, 3D boxes and volume, angles, vertical heights, or use the built-in spirit level.",
            ),
            OnboardingStep(
                icon = Icons.Rounded.CameraAlt,
                title = "Snap Photos & Export Specs",
                body = "Tap the camera button anytime to save an annotated photo straight to your gallery, or open History to rename items and export PDF or CSV dimension sheets.",
            ),
        )
    }
    var page by remember { mutableIntStateOf(0) }
    val current = steps[page]

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC090C12))
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF121721))
                .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(24.dp))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF00E5FF).copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = current.icon,
                    contentDescription = null,
                    tint = Color(0xFF00E5FF),
                    modifier = Modifier.size(34.dp),
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            Text(
                text = current.title,
                color = Color.White,
                fontSize = 21.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = current.body,
                color = Color(0xFFCBD5E1),
                fontSize = 14.sp,
                lineHeight = 20.sp,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(22.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                steps.indices.forEach { idx ->
                    Box(
                        modifier = Modifier
                            .size(if (idx == page) 10.dp else 7.dp)
                            .clip(CircleShape)
                            .background(if (idx == page) Color(0xFF00E5FF) else Color(0x44FFFFFF)),
                    )
                }
            }

            Spacer(modifier = Modifier.height(22.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onFinish) {
                    Text("Skip", color = Color(0xFF94A3B8))
                }
                Button(
                    onClick = {
                        if (page < steps.lastIndex) page++ else onFinish()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF00E5FF),
                        contentColor = Color(0xFF061018),
                    ),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text(
                        text = if (page < steps.lastIndex) "Next" else "Start Measuring",
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}
