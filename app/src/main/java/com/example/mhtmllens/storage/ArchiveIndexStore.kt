package com.example.mhtmllens.storage

import android.content.Context
import com.example.mhtmllens.model.ArchiveAccess
import com.example.mhtmllens.model.ArchiveFormat
import com.example.mhtmllens.model.ArchiveItem
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class FileFingerprint(
    val path: String,
    val sizeBytes: Long,
    val modifiedMillis: Long
)

data class ArchiveIndexSnapshot(
    val archives: List<ArchiveItem> = emptyList(),
    val fingerprints: Map<String, FileFingerprint> = emptyMap(),
    val lastDownloadsScanMillis: Long = 0L
)

class ArchiveIndexStore(context: Context) {
    private val indexFile = File(context.filesDir, "archive-index-v2.json")

    fun load(): ArchiveIndexSnapshot {
        if (!indexFile.isFile) return ArchiveIndexSnapshot()
        return runCatching {
            val root = JSONObject(indexFile.readText(Charsets.UTF_8))
            val archives = mutableListOf<ArchiveItem>()
            val archiveArray = root.optJSONArray("archives") ?: JSONArray()

            for (i in 0 until archiveArray.length()) {
                val obj = archiveArray.optJSONObject(i) ?: continue
                val access = runCatching {
                    ArchiveAccess.valueOf(obj.optString("access"))
                }.getOrNull() ?: continue
                val format = runCatching {
                    ArchiveFormat.valueOf(obj.optString("format"))
                }.getOrDefault(ArchiveFormat.MHTML)
                val locator = obj.optString("locator")
                if (locator.isBlank()) continue
                if (access == ArchiveAccess.FILE && !File(locator).isFile) continue

                archives += ArchiveItem(
                    id = obj.optString("id").ifBlank {
                        if (access == ArchiveAccess.FILE) "file:$locator" else "content:$locator"
                    },
                    displayName = obj.optString("displayName").ifBlank { File(locator).name },
                    locator = locator,
                    access = access,
                    format = format,
                    sizeBytes = obj.optLong("sizeBytes", 0L),
                    modifiedMillis = obj.optLong("modifiedMillis", 0L),
                    title = obj.optString("title").ifBlank {
                        obj.optString("displayName", "Offline document")
                    },
                    sourceUrl = obj.optString("sourceUrl").takeIf { it.isNotBlank() },
                    hasExtension = obj.optBoolean("hasExtension", false)
                )
            }

            val fingerprints = linkedMapOf<String, FileFingerprint>()
            val fileArray = root.optJSONArray("fingerprints") ?: JSONArray()
            for (i in 0 until fileArray.length()) {
                val obj = fileArray.optJSONObject(i) ?: continue
                val path = obj.optString("path")
                if (path.isBlank()) continue
                fingerprints[path] = FileFingerprint(
                    path = path,
                    sizeBytes = obj.optLong("sizeBytes", -1L),
                    modifiedMillis = obj.optLong("modifiedMillis", -1L)
                )
            }

            ArchiveIndexSnapshot(
                archives = archives.sortedByDescending { it.modifiedMillis },
                fingerprints = fingerprints,
                lastDownloadsScanMillis = root.optLong("lastDownloadsScanMillis", 0L)
            )
        }.getOrElse {
            ArchiveIndexSnapshot()
        }
    }

    fun save(snapshot: ArchiveIndexSnapshot) {
        val root = JSONObject()
            .put("version", 2)
            .put("lastDownloadsScanMillis", snapshot.lastDownloadsScanMillis)

        val archives = JSONArray()
        snapshot.archives.forEach { item ->
            archives.put(
                JSONObject()
                    .put("id", item.id)
                    .put("displayName", item.displayName)
                    .put("locator", item.locator)
                    .put("access", item.access.name)
                    .put("format", item.format.name)
                    .put("sizeBytes", item.sizeBytes)
                    .put("modifiedMillis", item.modifiedMillis)
                    .put("title", item.title)
                    .put("sourceUrl", item.sourceUrl ?: "")
                    .put("hasExtension", item.hasExtension)
            )
        }
        root.put("archives", archives)

        val fingerprints = JSONArray()
        snapshot.fingerprints.values.forEach { stamp ->
            fingerprints.put(
                JSONObject()
                    .put("path", stamp.path)
                    .put("sizeBytes", stamp.sizeBytes)
                    .put("modifiedMillis", stamp.modifiedMillis)
            )
        }
        root.put("fingerprints", fingerprints)

        val temp = File(indexFile.parentFile, indexFile.name + ".tmp")
        temp.writeText(root.toString(), Charsets.UTF_8)
        if (!temp.renameTo(indexFile)) {
            indexFile.writeText(root.toString(), Charsets.UTF_8)
            temp.delete()
        }
    }
}
