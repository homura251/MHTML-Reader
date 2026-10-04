package com.example.mhtmllens.scanner

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.documentfile.provider.DocumentFile
import com.example.mhtmllens.mhtml.MhtmlParser
import com.example.mhtmllens.model.ArchiveAccess
import com.example.mhtmllens.model.ArchiveFormat
import com.example.mhtmllens.model.ArchiveItem
import com.example.mhtmllens.storage.ArchiveIndexSnapshot
import com.example.mhtmllens.storage.FileFingerprint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.Locale

class ArchiveScanner(private val context: Context) {

    data class ScanResult(
        val items: List<ArchiveItem>,
        val filesChecked: Int,
        val fingerprints: Map<String, FileFingerprint> = emptyMap(),
        val permissionRequired: Boolean = false
    )

    suspend fun scanDownloads(previous: ArchiveIndexSnapshot = ArchiveIndexSnapshot()): ScanResult =
        withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                return@withContext ScanResult(
                    items = previous.archives,
                    filesChecked = 0,
                    fingerprints = previous.fingerprints,
                    permissionRequired = true
                )
            }
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            scanFiles(downloads, previous)
        }

    suspend fun scanTree(treeUri: Uri): ScanResult = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: return@withContext ScanResult(emptyList(), 0)
        val found = mutableListOf<ArchiveItem>()
        var checked = 0
        fun visit(node: DocumentFile, depth: Int) {
            if (depth > 16) return
            if (node.isDirectory) {
                node.listFiles().forEach { visit(it, depth + 1) }
            } else if (node.isFile) {
                checked++
                inspectContent(node.uri, node.name ?: "Offline document", node.length(), node.lastModified())
                    ?.let(found::add)
            }
        }
        visit(root, 0)
        ScanResult(found.sortedByDescending { it.modifiedMillis }, checked)
    }

    suspend fun inspectUri(uri: Uri): ArchiveItem? = withContext(Dispatchers.IO) {
        val doc = DocumentFile.fromSingleUri(context, uri)
        inspectContent(
            uri = uri,
            name = doc?.name ?: "Offline document",
            size = doc?.length() ?: 0L,
            modified = doc?.lastModified() ?: System.currentTimeMillis()
        )
    }

    fun open(item: ArchiveItem): InputStream? = when (item.access) {
        ArchiveAccess.FILE -> runCatching { FileInputStream(item.file) }.getOrNull()
        ArchiveAccess.CONTENT -> runCatching { context.contentResolver.openInputStream(item.uri) }.getOrNull()
    }

    fun readArchiveBytes(item: ArchiveItem): ByteArray {
        val input = open(item) ?: error("无法读取文件")
        input.use { stream ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                total += read
                if (total > MAX_ARCHIVE_BYTES) {
                    throw IllegalArgumentException("文件超过 ${MAX_ARCHIVE_BYTES / 1024 / 1024} MB 安全上限")
                }
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }
    }

    private fun scanFiles(root: File, previous: ArchiveIndexSnapshot): ScanResult {
        if (!root.exists() || !root.canRead()) {
            return ScanResult(previous.archives, 0, previous.fingerprints)
        }

        val previousFiles = previous.archives
            .filter { it.access == ArchiveAccess.FILE }
            .associateBy { it.locator }
        val preservedContent = previous.archives.filter { it.access == ArchiveAccess.CONTENT }
        val newFingerprints = linkedMapOf<String, FileFingerprint>()
        val found = mutableListOf<ArchiveItem>()
        var checked = 0

        root.walkTopDown()
            .maxDepth(16)
            .onEnter { dir -> !dir.name.startsWith('.') }
            .filter { it.isFile }
            .forEach { file ->
                checked++
                val path = file.absolutePath
                val stamp = FileFingerprint(path, file.length(), file.lastModified())
                newFingerprints[path] = stamp

                val oldStamp = previous.fingerprints[path]
                val unchanged = oldStamp != null &&
                    oldStamp.sizeBytes == stamp.sizeBytes &&
                    oldStamp.modifiedMillis == stamp.modifiedMillis

                if (unchanged) {
                    previousFiles[path]?.let(found::add)
                } else {
                    inspectFile(file)?.let(found::add)
                }
            }

        return ScanResult(
            items = (found + preservedContent)
                .distinctBy { it.id }
                .sortedByDescending { it.modifiedMillis },
            filesChecked = checked,
            fingerprints = newFingerprints
        )
    }

    private fun inspectFile(file: File): ArchiveItem? {
        if (file.length() < 16L || file.length() > MAX_ARCHIVE_BYTES) return null
        val prefix = runCatching { FileInputStream(file).use { it.readPrefix() } }.getOrNull() ?: return null
        val format = detectFormat(prefix) ?: return null
        val title = when (format) {
            ArchiveFormat.MHTML -> MhtmlParser.previewTitle(prefix)
            ArchiveFormat.HTML -> MhtmlParser.previewHtmlTitle(prefix)
        }.orEmpty().ifBlank { file.name }

        return ArchiveItem(
            id = "file:${file.absolutePath}",
            displayName = file.name,
            locator = file.absolutePath,
            access = ArchiveAccess.FILE,
            format = format,
            sizeBytes = file.length(),
            modifiedMillis = file.lastModified(),
            title = title,
            sourceUrl = if (format == ArchiveFormat.MHTML) snapshotLocation(prefix) else null,
            hasExtension = hasKnownExtension(file.name, format)
        )
    }

    private fun inspectContent(uri: Uri, name: String, size: Long, modified: Long): ArchiveItem? {
        if (size > MAX_ARCHIVE_BYTES) return null
        val prefix = context.contentResolver.openInputStream(uri)?.use { it.readPrefix() } ?: return null
        val format = detectFormat(prefix) ?: return null
        val title = when (format) {
            ArchiveFormat.MHTML -> MhtmlParser.previewTitle(prefix)
            ArchiveFormat.HTML -> MhtmlParser.previewHtmlTitle(prefix)
        }.orEmpty().ifBlank { name }

        return ArchiveItem(
            id = "content:$uri",
            displayName = name,
            locator = uri.toString(),
            access = ArchiveAccess.CONTENT,
            format = format,
            sizeBytes = size,
            modifiedMillis = modified,
            title = title,
            sourceUrl = if (format == ArchiveFormat.MHTML) snapshotLocation(prefix) else null,
            hasExtension = hasKnownExtension(name, format)
        )
    }

    private fun detectFormat(prefix: ByteArray): ArchiveFormat? = when {
        MhtmlParser.looksLikeMhtml(prefix) -> ArchiveFormat.MHTML
        MhtmlParser.looksLikeHtml(prefix) -> ArchiveFormat.HTML
        else -> null
    }

    private fun InputStream.readPrefix(): ByteArray {
        val buffer = ByteArray(PREFIX_BYTES)
        var total = 0
        while (total < buffer.size) {
            val read = read(buffer, total, buffer.size - total)
            if (read <= 0) break
            total += read
        }
        return buffer.copyOf(total)
    }

    private fun snapshotLocation(prefix: ByteArray): String? {
        val text = prefix.toString(Charsets.ISO_8859_1)
            .replace(Regex("\\r?\\n[ \\t]+"), " ")
        return Regex("(?im)^Snapshot-Content-Location:\\s*(.+)$")
            .find(text)?.groupValues?.getOrNull(1)?.trim()
    }

    private fun hasKnownExtension(name: String, format: ArchiveFormat): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return when (format) {
            ArchiveFormat.MHTML -> lower.endsWith(".mhtml") || lower.endsWith(".mht")
            ArchiveFormat.HTML -> lower.endsWith(".html") || lower.endsWith(".htm")
        }
    }

    companion object {
        const val PREFIX_BYTES = 512 * 1024
        const val MAX_ARCHIVE_BYTES = 256L * 1024L * 1024L
    }
}
