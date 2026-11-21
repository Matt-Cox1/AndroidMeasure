package dev.precisionmeasure.app.ui

import android.graphics.BitmapFactory
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.precisionmeasure.app.measurement.SceneItem
import dev.precisionmeasure.app.storage.SavedCaptureRecord
import dev.precisionmeasure.geometry.UnitSystem
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    sceneItems: List<SceneItem>,
    savedRecords: List<SavedCaptureRecord>,
    unit: UnitSystem,
    decimals: Int,
    onRenameSceneItem: (Long, String) -> Unit,
    onDeleteSceneItem: (Long) -> Unit,
    onClearScene: () -> Unit,
    onCopySceneText: () -> Unit,
    onExportSceneCsv: () -> Unit,
    onExportScenePdf: () -> Unit,
    onShareCapturePhoto: (SavedCaptureRecord) -> Unit,
    onExportCapturePdf: (SavedCaptureRecord) -> Unit,
    onRenameCapture: (Long, String) -> Unit,
    onDeleteCapture: (Long) -> Unit,
    onClose: () -> Unit,
) {
    var selectedTab by remember { mutableIntStateOf(if (sceneItems.isNotEmpty()) 0 else 1) }
    var renameTarget by remember { mutableStateOf<Triple<Boolean, Long, String>?>(null) } // (isSceneItem, id, currentTitle)
    val dateFmt = remember { SimpleDateFormat("MMM d, HH:mm", Locale.US) }

    Scaffold(
        containerColor = Color(0xFF0C1017),
        topBar = {
            TopAppBar(
                title = { Text("Measurements & Photos", fontWeight = FontWeight.Bold, color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0C1017)),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            PrimaryTabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color(0xFF0C1017),
                contentColor = Color(0xFF00E5FF),
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Current Scene (${sceneItems.size})") },
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Saved Photos (${savedRecords.size})") },
                )
            }

            if (selectedTab == 0) {
                if (sceneItems.isEmpty()) {
                    EmptyStateBox("No completed measurements in this scene yet.\nMeasure a line, perimeter, box, angle, or height on the camera screen.")
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ExportActionButton(Icons.Rounded.ContentCopy, "Copy", onCopySceneText, Modifier.weight(1f))
                        ExportActionButton(Icons.Rounded.PictureAsPdf, "PDF", onExportScenePdf, Modifier.weight(1f))
                        ExportActionButton(Icons.Rounded.TableChart, "CSV", onExportSceneCsv, Modifier.weight(1f))
                        ExportActionButton(
                            Icons.Rounded.DeleteOutline,
                            "Clear",
                            onClearScene,
                            Modifier.weight(1f),
                            tint = Color(0xFFFF8A80),
                        )
                    }

                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(sceneItems, key = { it.id }) { item ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(Color(0xFF141A24))
                                    .border(1.dp, Color(0x18FFFFFF), RoundedCornerShape(16.dp))
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(4.dp)
                                        .height(42.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(Color(item.color.argb)),
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(item.label, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = item.mode.label.uppercase(),
                                            color = Color(0xFF94A3B8),
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                    Text(
                                        text = item.formatPrimary(unit, decimals),
                                        color = Color(0xFF00E5FF),
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                    item.formatSecondary(unit, decimals)?.let { sec ->
                                        Text(sec, color = Color(0xFFCBD5E1), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                                    }
                                }
                                IconButton(onClick = { renameTarget = Triple(true, item.id, item.label) }) {
                                    Icon(Icons.Rounded.Edit, contentDescription = "Rename", tint = Color(0xFF94A3B8))
                                }
                                IconButton(onClick = { onDeleteSceneItem(item.id) }) {
                                    Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete", tint = Color(0xFFFF8A80))
                                }
                            }
                        }
                        item { Spacer(modifier = Modifier.height(20.dp)) }
                    }
                }
            } else {
                if (savedRecords.isEmpty()) {
                    EmptyStateBox("No photo snapshots yet.\nTap the camera button on the viewfinder to save annotated photos to your gallery.")
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(savedRecords, key = { it.id }) { record ->
                            SavedCaptureCard(
                                record = record,
                                dateText = dateFmt.format(Date(record.timestampMs)),
                                onShare = { onShareCapturePhoto(record) },
                                onPdf = { onExportCapturePdf(record) },
                                onRename = { renameTarget = Triple(false, record.id, record.title) },
                                onDelete = { onDeleteCapture(record.id) },
                            )
                        }
                    }
                }
            }
        }
    }

    renameTarget?.let { (isScene, id, currentTitle) ->
        var text by remember(id) { mutableStateOf(currentTitle) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text("Label") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (isScene) onRenameSceneItem(id, text) else onRenameCapture(id, text)
                        renameTarget = null
                    },
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SavedCaptureCard(
    record: SavedCaptureRecord,
    dateText: String,
    onShare: () -> Unit,
    onPdf: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val thumb = remember(record.photoPath) {
        record.photoPath?.let { path ->
            val file = File(path)
            if (file.exists()) {
                val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                BitmapFactory.decodeFile(path, opts)
            } else {
                null
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF141A24))
            .border(1.dp, Color(0x18FFFFFF), RoundedCornerShape(18.dp))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(74.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF0A0D12)),
                contentAlignment = Alignment.Center,
            ) {
                if (thumb != null) {
                    Image(
                        bitmap = thumb.asImageBitmap(),
                        contentDescription = record.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(record.title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(record.summary, color = Color(0xFF00E5FF), fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                Text(
                    text = "$dateText  ·  ${record.calibrationLabel}",
                    color = Color(0xFF94A3B8),
                    fontSize = 11.sp,
                )
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ExportActionButton(Icons.Rounded.Share, "Share", onShare, Modifier.weight(1f))
            ExportActionButton(Icons.Rounded.PictureAsPdf, "PDF", onPdf, Modifier.weight(1f))
            ExportActionButton(Icons.Rounded.Edit, "Rename", onRename, Modifier.weight(1f))
            ExportActionButton(Icons.Rounded.DeleteOutline, "Delete", onDelete, Modifier.weight(1f), tint = Color(0xFFFF8A80))
        }
    }
}

@Composable
private fun ExportActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Color(0xFF00E5FF),
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF1C2432))
            .clickable { onClick() }
            .padding(vertical = 8.dp, horizontal = 6.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(4.dp))
        Text(label, color = tint, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun EmptyStateBox(message: String) {
    Box(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            color = Color(0xFF94A3B8),
            fontSize = 14.sp,
            lineHeight = 21.sp,
        )
    }
}
