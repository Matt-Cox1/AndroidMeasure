package dev.precisionmeasure.app.storage

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class SavedCaptureRecord(
    val id: Long,
    val timestampMs: Long,
    val title: String,
    val summary: String,
    val itemSummaries: List<String>,
    val photoPath: String?,
    val galleryUri: String?,
    val calibrationLabel: String,
)

/**
 * Lightweight persistent JSON store for saved measurement snapshots and sessions.
 * Keeps data 100% offline in app-private storage.
 */
class HistoryStore(context: Context) {

    private val snapshotsDir = File(context.filesDir, "snapshots").apply { mkdirs() }
    private val indexFile = File(context.filesDir, "history_v1.json")

    private val _records = MutableStateFlow<List<SavedCaptureRecord>>(emptyList())
    val records: StateFlow<List<SavedCaptureRecord>> = _records.asStateFlow()

    init {
        _records.value = loadFromDisk()
    }

    fun newSnapshotFile(id: Long): File = File(snapshotsDir, "capture_$id.jpg")

    fun addRecord(record: SavedCaptureRecord) {
        val updated = listOf(record) + _records.value
        _records.value = updated
        persistToDisk(updated)
    }

    fun renameRecord(id: Long, newTitle: String) {
        val clean = newTitle.trim().ifEmpty { return }
        val updated = _records.value.map { if (it.id == id) it.copy(title = clean) else it }
        _records.value = updated
        persistToDisk(updated)
    }

    fun deleteRecord(id: Long) {
        val existing = _records.value.firstOrNull { it.id == id }
        existing?.photoPath?.let { path ->
            val file = File(path)
            if (file.exists()) file.delete()
        }
        val updated = _records.value.filterNot { it.id == id }
        _records.value = updated
        persistToDisk(updated)
    }

    private fun loadFromDisk(): List<SavedCaptureRecord> {
        if (!indexFile.exists()) return emptyList()
        val text = indexFile.readText()
        if (text.isBlank()) return emptyList()
        val arr = JSONArray(text)
        val list = ArrayList<SavedCaptureRecord>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val itemsJson = obj.optJSONArray("items") ?: JSONArray()
            val items = List(itemsJson.length()) { idx -> itemsJson.getString(idx) }
            list.add(
                SavedCaptureRecord(
                    id = obj.getLong("id"),
                    timestampMs = obj.getLong("timestampMs"),
                    title = obj.getString("title"),
                    summary = obj.getString("summary"),
                    itemSummaries = items,
                    photoPath = obj.optString("photoPath").takeIf { it.isNotBlank() },
                    galleryUri = obj.optString("galleryUri").takeIf { it.isNotBlank() },
                    calibrationLabel = obj.optString("calibrationLabel", "Uncalibrated VIO"),
                ),
            )
        }
        return list
    }

    private fun persistToDisk(records: List<SavedCaptureRecord>) {
        val arr = JSONArray()
        for (r in records) {
            val itemsArr = JSONArray()
            for (s in r.itemSummaries) itemsArr.put(s)
            val obj = JSONObject().apply {
                put("id", r.id)
                put("timestampMs", r.timestampMs)
                put("title", r.title)
                put("summary", r.summary)
                put("items", itemsArr)
                put("photoPath", r.photoPath ?: "")
                put("galleryUri", r.galleryUri ?: "")
                put("calibrationLabel", r.calibrationLabel)
            }
            arr.put(obj)
        }
        indexFile.writeText(arr.toString(2))
    }
}
