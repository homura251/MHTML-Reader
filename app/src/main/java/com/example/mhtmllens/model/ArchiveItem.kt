package com.example.mhtmllens.model

import android.net.Uri
import java.io.File

enum class ArchiveAccess { FILE, CONTENT }

enum class ArchiveFormat { MHTML, HTML }

data class ArchiveItem(
    val id: String,
    val displayName: String,
    val locator: String,
    val access: ArchiveAccess,
    val format: ArchiveFormat = ArchiveFormat.MHTML,
    val sizeBytes: Long,
    val modifiedMillis: Long,
    val title: String,
    val sourceUrl: String? = null,
    val hasExtension: Boolean = false
) {
    val uri: Uri get() = Uri.parse(locator)
    val file: File get() = File(locator)
}
