package com.example.mhtmllens

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.mhtmllens.mhtml.MhtmlParser
import com.example.mhtmllens.model.ArchiveFormat
import com.example.mhtmllens.model.ArchiveItem
import com.example.mhtmllens.scanner.ArchiveScanner
import com.example.mhtmllens.storage.ArchiveIndexSnapshot
import com.example.mhtmllens.storage.ArchiveIndexStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LoadedArchive(val item: ArchiveItem, val archive: MhtmlParser.Archive)

data class AppUiState(
    val archives: List<ArchiveItem> = emptyList(),
    val isScanning: Boolean = false,
    val filesChecked: Int = 0,
    val permissionRequired: Boolean = false,
    val loadingId: String? = null,
    val selected: LoadedArchive? = null,
    val error: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val scanner = ArchiveScanner(application)
    private val store = ArchiveIndexStore(application)
    private var snapshot: ArchiveIndexSnapshot = store.load()

    private val _ui = MutableStateFlow(
        AppUiState(
            archives = snapshot.archives,
            filesChecked = snapshot.fingerprints.size
        )
    )
    val ui: StateFlow<AppUiState> = _ui.asStateFlow()

    fun scanDownloads() {
        if (_ui.value.isScanning) return
        viewModelScope.launch {
            _ui.update { it.copy(isScanning = true, error = null) }
            val result = runCatching { scanner.scanDownloads(snapshot) }
            result.onSuccess { scan ->
                if (!scan.permissionRequired) {
                    snapshot = ArchiveIndexSnapshot(
                        archives = scan.items,
                        fingerprints = scan.fingerprints,
                        lastDownloadsScanMillis = System.currentTimeMillis()
                    )
                    persistSnapshot()
                }
                _ui.update {
                    it.copy(
                        archives = scan.items,
                        isScanning = false,
                        filesChecked = scan.filesChecked,
                        permissionRequired = scan.permissionRequired
                    )
                }
            }.onFailure { e ->
                _ui.update { it.copy(isScanning = false, error = e.userMessage()) }
            }
        }
    }

    fun scanTree(uri: Uri) {
        takeReadPermission(uri)
        viewModelScope.launch {
            _ui.update { it.copy(isScanning = true, error = null, permissionRequired = false) }
            runCatching { scanner.scanTree(uri) }
                .onSuccess { scan ->
                    val merged = (scan.items + snapshot.archives)
                        .distinctBy { it.id }
                        .sortedByDescending { it.modifiedMillis }
                    snapshot = snapshot.copy(archives = merged)
                    persistSnapshot()
                    _ui.update {
                        it.copy(
                            archives = merged,
                            isScanning = false,
                            filesChecked = scan.filesChecked
                        )
                    }
                }
                .onFailure { e -> _ui.update { it.copy(isScanning = false, error = e.userMessage()) } }
        }
    }

    fun addAndOpen(uri: Uri) {
        takeReadPermission(uri)
        viewModelScope.launch {
            _ui.update { it.copy(error = null, loadingId = uri.toString()) }
            val item = runCatching { scanner.inspectUri(uri) }.getOrNull()
            if (item == null) {
                _ui.update {
                    it.copy(
                        loadingId = null,
                        error = "这个文件不像 MHTML / MHT / HTML / HTM 文档。"
                    )
                }
                return@launch
            }

            val merged = (listOf(item) + snapshot.archives.filterNot { it.id == item.id })
                .sortedByDescending { it.modifiedMillis }
            snapshot = snapshot.copy(archives = merged)
            persistSnapshot()
            _ui.update { it.copy(archives = merged) }
            open(item)
        }
    }

    fun open(item: ArchiveItem) {
        viewModelScope.launch {
            _ui.update { it.copy(loadingId = item.id, error = null) }
            val parsed = runCatching {
                withContext(Dispatchers.IO) {
                    val bytes = scanner.readArchiveBytes(item)
                    when (item.format) {
                        ArchiveFormat.MHTML -> MhtmlParser.parse(bytes)
                        ArchiveFormat.HTML -> MhtmlParser.parseHtml(
                            bytes = bytes,
                            fallbackTitle = item.displayName
                        )
                    }
                }
            }

            parsed.onSuccess { archive ->
                val updated = snapshot.archives.map { old ->
                    if (old.id == item.id && archive.title.isNotBlank()) {
                        old.copy(
                            title = archive.title,
                            sourceUrl = archive.rootUrl ?: old.sourceUrl
                        )
                    } else old
                }
                snapshot = snapshot.copy(archives = updated)
                persistSnapshot()
                val selectedItem = updated.firstOrNull { it.id == item.id } ?: item
                _ui.update {
                    it.copy(
                        loadingId = null,
                        selected = LoadedArchive(selectedItem, archive),
                        archives = updated
                    )
                }
            }.onFailure { e ->
                _ui.update { it.copy(loadingId = null, error = "打开失败：${e.userMessage()}") }
            }
        }
    }

    fun closeArchive() {
        _ui.update { it.copy(selected = null) }
    }

    fun clearError() {
        _ui.update { it.copy(error = null) }
    }

    fun scanIfReady() {
        val canAutoScan = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            Environment.isExternalStorageManager()

        // Auto-scan only once on a fresh install/index. Existing cached results are
        // shown immediately on later launches; the user can manually request an
        // incremental refresh without rereading unchanged files.
        if (
            canAutoScan &&
            snapshot.lastDownloadsScanMillis == 0L &&
            !_ui.value.isScanning
        ) {
            scanDownloads()
        }
    }

    private fun persistSnapshot() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { store.save(snapshot) }
        }
    }

    private fun takeReadPermission(uri: Uri) {
        runCatching {
            getApplication<Application>().contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }

    private fun Throwable.userMessage(): String =
        localizedMessage?.takeIf { it.isNotBlank() } ?: javaClass.simpleName
}
