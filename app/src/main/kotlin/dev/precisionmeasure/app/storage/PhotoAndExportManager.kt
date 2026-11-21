package dev.precisionmeasure.app.storage

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import dev.precisionmeasure.app.measurement.FloatingLabelOverlay
import dev.precisionmeasure.app.measurement.SceneItem
import dev.precisionmeasure.geometry.MeasurementQuality
import dev.precisionmeasure.geometry.UnitFormatter
import dev.precisionmeasure.geometry.UnitSystem
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class PhotoCaptureOutcome(
    val record: SavedCaptureRecord,
    val savedToGallery: Boolean,
)

/**
 * Composites crisp vector labels onto captured OpenGL frames, saves annotated photos to the
 * Android Gallery (Pictures/AndroidMeasure), and exports PDF spec sheets, CSV tables, and text.
 */
class PhotoAndExportManager(
    private val context: Context,
    private val historyStore: HistoryStore,
) {

    private val fileDateFmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    private val readableDateFmt = SimpleDateFormat("MMM d, yyyy  HH:mm", Locale.US)

    fun saveAnnotatedSnapshot(
        rawGlBitmap: Bitmap,
        labels: List<FloatingLabelOverlay>,
        summaryBanner: String?,
        sceneSummaries: List<String>,
        calibrationLabel: String,
        saveToGallery: Boolean,
        saveCleanCopy: Boolean,
    ): PhotoCaptureOutcome {
        val id = System.currentTimeMillis()
        val annotated = rawGlBitmap.copy(Bitmap.Config.ARGB_8888, true)
        drawAnnotationsOnBitmap(annotated, labels, summaryBanner, calibrationLabel)

        val localFile = historyStore.newSnapshotFile(id)
        FileOutputStream(localFile).use { out ->
            annotated.compress(Bitmap.CompressFormat.JPEG, 94, out)
        }

        var galleryUri: Uri? = null
        if (saveToGallery) {
            val stamp = fileDateFmt.format(Date(id))
            galleryUri = saveBitmapToGallery(annotated, "AndroidMeasure_$stamp.jpg")
            if (saveCleanCopy) {
                saveBitmapToGallery(rawGlBitmap, "AndroidMeasure_${stamp}_clean.jpg")
            }
        }
        annotated.recycle()

        val title = "Capture ${readableDateFmt.format(Date(id))}"
        val summary = summaryBanner ?: sceneSummaries.firstOrNull() ?: "Camera snapshot"
        val record = SavedCaptureRecord(
            id = id,
            timestampMs = id,
            title = title,
            summary = summary,
            itemSummaries = sceneSummaries,
            photoPath = localFile.absolutePath,
            galleryUri = galleryUri?.toString(),
            calibrationLabel = calibrationLabel,
        )
        historyStore.addRecord(record)
        return PhotoCaptureOutcome(record = record, savedToGallery = galleryUri != null)
    }

    fun copyDimensionsToClipboard(items: List<SceneItem>, unit: UnitSystem, decimals: Int, calibrationLabel: String) {
        val text = buildPlainTextReport(items, unit, decimals, calibrationLabel)
        val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cb.setPrimaryClip(ClipData.newPlainText("AndroidMeasure Dimensions", text))
    }

    fun sharePhotoFile(photoPath: String, title: String) {
        val file = File(photoPath)
        if (!file.exists()) return
        val uri = FileProvider.getUriForFile(context, AUTHORITY, file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share Measurement Photo"))
    }

    fun exportAndShareCsv(
        items: List<SceneItem>,
        unit: UnitSystem,
        decimals: Int,
        calibrationLabel: String,
    ) {
        val exportsDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val stamp = fileDateFmt.format(Date())
        val csvFile = File(exportsDir, "AndroidMeasure_$stamp.csv")
        val sb = StringBuilder()
        sb.appendLine("Index,Label,Mode,Primary,Details,Calibration")
        items.forEachIndexed { idx, item ->
            val primary = item.formatPrimary(unit, decimals).replace(",", " ")
            val detail = item.formatSecondary(unit, decimals).orEmpty().replace(",", " ")
            val safeLabel = item.label.replace(",", " ")
            val safeCal = calibrationLabel.replace(",", " ")
            sb.appendLine("${idx + 1},\"$safeLabel\",${item.mode.label},\"$primary\",\"$detail\",\"$safeCal\"")
        }
        csvFile.writeText(sb.toString())

        val uri = FileProvider.getUriForFile(context, AUTHORITY, csvFile)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "AndroidMeasure Dimensions CSV")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Export CSV"))
    }

    fun exportAndSharePdfSpecSheet(
        title: String,
        photoPath: String?,
        rows: List<Pair<String, String>>,
        calibrationLabel: String,
    ) {
        val doc = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create() // A4 portrait @ 72 dpi
        val page = doc.startPage(pageInfo)
        val canvas = page.canvas

        val bgPaint = Paint().apply { color = Color.WHITE }
        canvas.drawRect(0f, 0f, 595f, 842f, bgPaint)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0F172A")
            textSize = 20f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#475569")
            textSize = 10.5f
        }
        val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0F172A")
            textSize = 12f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1E293B")
            textSize = 11f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        }
        val dividerPaint = Paint().apply {
            color = Color.parseColor("#CBD5E1")
            strokeWidth = 1f
        }

        var y = 44f
        canvas.drawText("AndroidMeasure Specification Sheet", 36f, y, titlePaint)
        y += 18f
        canvas.drawText("$title  |  Scale: $calibrationLabel", 36f, y, subPaint)
        y += 14f
        canvas.drawLine(36f, y, 559f, y, dividerPaint)
        y += 16f

        if (photoPath != null) {
            val file = File(photoPath)
            if (file.exists()) {
                val bmp = BitmapFactory.decodeFile(file.absolutePath)
                if (bmp != null) {
                    val maxW = 523f
                    val maxH = 380f
                    val scale = minOf(maxW / bmp.width.toFloat(), maxH / bmp.height.toFloat())
                    val drawW = bmp.width * scale
                    val drawH = bmp.height * scale
                    val left = 36f + (maxW - drawW) * 0.5f
                    val dest = RectF(left, y, left + drawW, y + drawH)
                    canvas.drawBitmap(bmp, Rect(0, 0, bmp.width, bmp.height), dest, null)
                    bmp.recycle()
                    y += drawH + 20f
                }
            }
        }

        canvas.drawText("Measured Dimensions", 36f, y, headerPaint)
        y += 8f
        canvas.drawLine(36f, y, 559f, y, dividerPaint)
        y += 18f

        if (rows.isEmpty()) {
            canvas.drawText("No saved measurements in this capture.", 36f, y, subPaint)
        } else {
            for ((name, value) in rows) {
                if (y > 800f) break
                canvas.drawText(name, 36f, y, headerPaint)
                canvas.drawText(value, 220f, y, bodyPaint)
                y += 18f
            }
        }

        doc.finishPage(page)

        val exportsDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val stamp = fileDateFmt.format(Date())
        val pdfFile = File(exportsDir, "AndroidMeasure_$stamp.pdf")
        FileOutputStream(pdfFile).use { out -> doc.writeTo(out) }
        doc.close()

        val uri = FileProvider.getUriForFile(context, AUTHORITY, pdfFile)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "AndroidMeasure Spec Sheet")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share PDF Spec Sheet"))
    }

    private fun buildPlainTextReport(
        items: List<SceneItem>,
        unit: UnitSystem,
        decimals: Int,
        calibrationLabel: String,
    ): String {
        val sb = StringBuilder()
        sb.appendLine("AndroidMeasure (${readableDateFmt.format(Date())})")
        sb.appendLine("Scale: $calibrationLabel")
        items.forEachIndexed { idx, item ->
            val primary = item.formatPrimary(unit, decimals)
            val secondary = item.formatSecondary(unit, decimals)
            if (secondary != null) {
                sb.appendLine("${idx + 1}. ${item.label}: $primary ($secondary)")
            } else {
                sb.appendLine("${idx + 1}. ${item.label}: $primary")
            }
        }
        return sb.toString().trim()
    }

    private fun drawAnnotationsOnBitmap(
        bitmap: Bitmap,
        labels: List<FloatingLabelOverlay>,
        summaryBanner: String?,
        calibrationLabel: String,
    ) {
        val canvas = Canvas(bitmap)
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 36f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#B2EBF2")
            textSize = 24f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
        }
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }

        for (lbl in labels) {
            val w1 = labelPaint.measureText(lbl.line1)
            val w2 = if (lbl.line2 != null) subPaint.measureText(lbl.line2) else 0f
            val pillW = maxOf(w1, w2) + 44f
            val pillH = if (lbl.line2 != null) 82f else 54f
            val cx = lbl.screenCenter.x.toFloat()
            val cy = lbl.screenCenter.y.toFloat()
            val rect = RectF(cx - pillW * 0.5f, cy - pillH * 0.5f, cx + pillW * 0.5f, cy + pillH * 0.5f)

            bgPaint.color = Color.argb(220, 14, 18, 24)
            borderPaint.color = when {
                lbl.isPreview -> Color.argb(220, 255, 255, 255)
                !lbl.isActive -> Color.argb(210, 77, 208, 225)
                lbl.quality == MeasurementQuality.LOCKED -> Color.argb(255, 0, 230, 118)
                lbl.quality == MeasurementQuality.GOOD -> Color.argb(255, 255, 171, 0)
                else -> Color.argb(255, 0, 229, 255)
            }
            canvas.drawRoundRect(rect, 16f, 16f, bgPaint)
            canvas.drawRoundRect(rect, 16f, 16f, borderPaint)

            if (lbl.line2 != null) {
                canvas.drawText(lbl.line1, cx, cy - 4f, labelPaint)
                canvas.drawText(lbl.line2, cx, cy + 26f, subPaint)
            } else {
                canvas.drawText(lbl.line1, cx, cy + 12f, labelPaint)
            }
        }

        // Clean bottom watermark bar on exported photo.
        val barH = 92f
        val barRect = RectF(0f, bitmap.height - barH, bitmap.width.toFloat(), bitmap.height.toFloat())
        bgPaint.color = Color.argb(195, 10, 14, 20)
        canvas.drawRect(barRect, bgPaint)

        val brandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 30f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.LEFT
        }
        val metaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#94A3B8")
            textSize = 22f
            textAlign = Paint.Align.LEFT
        }
        val headerText = summaryBanner ?: "AndroidMeasure"
        canvas.drawText(headerText, 32f, bitmap.height - 50f, brandPaint)
        canvas.drawText(
            "AndroidMeasure  •  ${readableDateFmt.format(Date())}  •  $calibrationLabel",
            32f,
            bitmap.height - 20f,
            metaPaint,
        )
    }

    private fun saveBitmapToGallery(bitmap: Bitmap, displayName: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/AndroidMeasure")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 94, out)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        return uri
    }

    companion object {
        private const val AUTHORITY = "dev.precisionmeasure.app.fileprovider"
    }
}
