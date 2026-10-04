package com.example.mhtmllens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.mhtmllens.browser.ArchiveWebView
import com.example.mhtmllens.model.ArchiveFormat
import com.example.mhtmllens.model.ArchiveItem
import com.example.mhtmllens.ui.theme.MhtmlLensTheme
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    private val openFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::addAndOpen)
    }

    private val openTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(vm::scanTree)
    }

    private val manageFiles = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        vm.scanIfReady()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MhtmlLensTheme {
                MhtmlLensApp(
                    vm = vm,
                    onPickFile = { openFile.launch(arrayOf("*/*")) },
                    onPickFolder = { openTree.launch(null) },
                    onRequestAllFiles = ::requestAllFilesAccess
                )
            }
        }

        intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data?.let(vm::addAndOpen)
        vm.scanIfReady()
    }

    override fun onResume() {
        super.onResume()
        vm.scanIfReady()
    }

    private fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            openTree.launch(null)
            return
        }
        val appIntent = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:$packageName")
        )
        runCatching { manageFiles.launch(appIntent) }
            .onFailure { manageFiles.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
    }
}

private enum class LibraryFilter { ALL, NO_EXTENSION, NAMED }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MhtmlLensApp(
    vm: MainViewModel,
    onPickFile: () -> Unit,
    onPickFolder: () -> Unit,
    onRequestAllFiles: () -> Unit
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(ui.error) {
        val message = ui.error ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        vm.clearError()
    }

    BackHandler(enabled = ui.selected != null) { vm.closeArchive() }

    AnimatedContent(
        targetState = ui.selected,
        transitionSpec = {
            (fadeIn(tween(220)) + scaleIn(tween(220), initialScale = 0.985f)) togetherWith
                (fadeOut(tween(160)) + scaleOut(tween(160), targetScale = 0.99f))
        },
        label = "library-reader"
    ) { selected ->
        if (selected == null) {
            LibraryScreen(
                ui = ui,
                snackbar = snackbar,
                onScan = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) vm::scanDownloads else onPickFolder,
                onPickFile = onPickFile,
                onPickFolder = onPickFolder,
                onRequestAllFiles = onRequestAllFiles,
                onOpen = vm::open
            )
        } else {
            ReaderScreen(
                loaded = selected,
                snackbar = snackbar,
                onBack = vm::closeArchive
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun LibraryScreen(
    ui: AppUiState,
    snackbar: SnackbarHostState,
    onScan: () -> Unit,
    onPickFile: () -> Unit,
    onPickFolder: () -> Unit,
    onRequestAllFiles: () -> Unit,
    onOpen: (ArchiveItem) -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(LibraryFilter.ALL) }

    val filtered = remember(ui.archives, query, filter) {
        ui.archives.filter { item ->
            val matchesFilter = when (filter) {
                LibraryFilter.ALL -> true
                LibraryFilter.NO_EXTENSION -> !item.hasExtension
                LibraryFilter.NAMED -> item.hasExtension
            }
            val q = query.trim()
            val matchesQuery = q.isBlank() ||
                item.title.contains(q, ignoreCase = true) ||
                item.displayName.contains(q, ignoreCase = true) ||
                item.sourceUrl.orEmpty().contains(q, ignoreCase = true)
            matchesFilter && matchesQuery
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onScan,
                icon = { Icon(Icons.Rounded.Refresh, contentDescription = null) },
                text = { Text("扫描") }
            )
        },
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("MHTML Reader", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "MHTML / HTML · 按内容识别",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = 12.dp,
                bottom = 104.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "summary") {
                SummaryCard(
                    count = ui.archives.size,
                    checked = ui.filesChecked,
                    scanning = ui.isScanning,
                    permissionRequired = ui.permissionRequired,
                    onScan = onScan,
                    onPickFile = onPickFile,
                    onPickFolder = onPickFolder,
                    onRequestAllFiles = onRequestAllFiles
                )
            }

            item(key = "search") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                        trailingIcon = {
                            AnimatedVisibility(query.isNotEmpty()) {
                                IconButton(onClick = { query = "" }) {
                                    Icon(Icons.Rounded.Close, contentDescription = "清空搜索")
                                }
                            }
                        },
                        placeholder = { Text("搜索标题、文件名或来源") },
                        shape = RoundedCornerShape(28.dp)
                    )

                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = filter == LibraryFilter.ALL,
                            onClick = { filter = LibraryFilter.ALL },
                            label = { Text("全部 ${ui.archives.size}") }
                        )
                        val noExt = ui.archives.count { !it.hasExtension }
                        FilterChip(
                            selected = filter == LibraryFilter.NO_EXTENSION,
                            onClick = { filter = LibraryFilter.NO_EXTENSION },
                            label = { Text("无后缀 $noExt") }
                        )
                        FilterChip(
                            selected = filter == LibraryFilter.NAMED,
                            onClick = { filter = LibraryFilter.NAMED },
                            label = { Text("有后缀") }
                        )
                    }
                }
            }

            if (filtered.isEmpty() && !ui.isScanning) {
                item(key = "empty") {
                    EmptyLibrary(
                        hasAny = ui.archives.isNotEmpty(),
                        onPickFile = onPickFile
                    )
                }
            } else {
                items(filtered, key = { it.id }) { item ->
                    ArchiveRow(
                        item = item,
                        loading = ui.loadingId == item.id,
                        onClick = { onOpen(item) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(
    count: Int,
    checked: Int,
    scanning: Boolean,
    permissionRequired: Boolean,
    onScan: () -> Unit,
    onPickFile: () -> Unit,
    onPickFolder: () -> Unit,
    onRequestAllFiles: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.Description,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (permissionRequired) "允许扫描 Downloads" else "$count 个离线网页",
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Text(
                        if (permissionRequired) {
                            "Android 11+ 需要“所有文件访问”才能自动发现 Chrome 保存的无后缀网页。"
                        } else if (checked > 0) {
                            "已检查 $checked 个文件；未变化文件使用本地索引，不重复读取内容。"
                        } else {
                            "支持 MHTML、HTML 和无后缀网页文件；结果会保存在本地索引。"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f)
                    )
                }
            }

            AnimatedVisibility(scanning) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (permissionRequired) {
                    Button(onClick = onRequestAllFiles) { Text("授权并扫描") }
                } else {
                    Button(onClick = onScan, enabled = !scanning) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (scanning) "正在扫描" else "扫描 Downloads")
                    }
                }
                OutlinedButton(onClick = onPickFile) { Text("打开文件") }
            }

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                TextButton(onClick = onPickFolder) {
                    Icon(Icons.Rounded.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("选择文件夹扫描")
                }
            }
        }
    }
}

@Composable
private fun ArchiveRow(item: ArchiveItem, loading: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !loading, onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        if (item.hasExtension) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.tertiaryContainer
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
                } else {
                    Icon(Icons.Rounded.Description, contentDescription = null)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                val secondary = sourceHost(item.sourceUrl) ?: item.displayName
                Text(
                    secondary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusPill(
                        when (item.format) {
                            ArchiveFormat.MHTML -> if (item.hasExtension) "MHTML" else "MHTML · 无后缀"
                            ArchiveFormat.HTML -> if (item.hasExtension) "HTML" else "HTML · 无后缀"
                        }
                    )
                    Text(
                        "${formatBytes(item.sizeBytes)} · ${formatDate(item.modifiedMillis)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusPill(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 7.dp, vertical = 3.dp)
    )
}

@Composable
private fun EmptyLibrary(hasAny: Boolean, onPickFile: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            if (hasAny) Icons.Rounded.Search else Icons.Rounded.Folder,
            contentDescription = null,
            modifier = Modifier.size(42.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            if (hasAny) "没有匹配的网页" else "还没有发现离线网页",
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            if (hasAny) "换个关键词或筛选条件。" else "可以扫描 Downloads，也可以直接打开 MHTML / HTML / 无后缀网页文件。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (!hasAny) TextButton(onClick = onPickFile) { Text("打开一个文件") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderScreen(
    loaded: LoadedArchive,
    snackbar: SnackbarHostState,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val archive = loaded.archive
    var showSearch by rememberSaveable(loaded.item.id) { mutableStateOf(false) }
    var searchQuery by rememberSaveable(loaded.item.id) { mutableStateOf("") }
    var findStep by rememberSaveable(loaded.item.id) { mutableIntStateOf(0) }
    var findForward by rememberSaveable(loaded.item.id) { mutableStateOf(true) }
    var activeMatch by remember { mutableIntStateOf(0) }
    var matchCount by remember { mutableIntStateOf(0) }
    var showSettings by remember { mutableStateOf(false) }
    var charsetOverride by rememberSaveable(loaded.item.id) { mutableStateOf<String?>(null) }
    var scriptsEnabled by rememberSaveable(loaded.item.id) { mutableStateOf(false) }
    var blockedUrl by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(blockedUrl) {
        val url = blockedUrl ?: return@LaunchedEffect
        val uri = runCatching { Uri.parse(url) }.getOrNull()
        val canOpenExternally = uri?.scheme.equals("http", ignoreCase = true) ||
            uri?.scheme.equals("https", ignoreCase = true)
        val result = snackbar.showSnackbar(
            message = if (canOpenExternally) "这个链接不在离线存档中" else "已拦截存档中的外部协议链接",
            actionLabel = if (canOpenExternally) "浏览器打开" else null,
            withDismissAction = true
        )
        if (canOpenExternally && result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
        }
        blockedUrl = null
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column {
                CenterAlignedTopAppBar(
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Rounded.ArrowBack, contentDescription = "返回")
                        }
                    },
                    title = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                archive.title,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                sourceHost(archive.rootUrl) ?: if (loaded.item.format == ArchiveFormat.HTML) "离线 HTML" else "离线 MHTML",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { showSearch = !showSearch }) {
                            Icon(Icons.Rounded.Search, contentDescription = "页内搜索")
                        }
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = "阅读设置")
                        }
                    }
                )

                AnimatedVisibility(showSearch) {
                    PageSearchBar(
                        query = searchQuery,
                        active = activeMatch,
                        total = matchCount,
                        onQuery = { searchQuery = it },
                        onPrevious = {
                            findForward = false
                            findStep++
                        },
                        onNext = {
                            findForward = true
                            findStep++
                        },
                        onClose = {
                            showSearch = false
                            searchQuery = ""
                        }
                    )
                }
            }
        },
        bottomBar = {
            ReaderStatusBar(
                charset = charsetOverride ?: archive.rootCharset,
                auto = charsetOverride == null,
                scriptsEnabled = scriptsEnabled,
                onSettings = { showSettings = true }
            )
        }
    ) { inner ->
        ArchiveWebView(
            archive = archive,
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
            charsetOverride = charsetOverride,
            javaScriptEnabled = scriptsEnabled,
            searchQuery = searchQuery,
            findStep = findStep,
            findForward = findForward,
            onFindResult = { active, total ->
                activeMatch = if (total > 0) active + 1 else 0
                matchCount = total
            },
            onBlockedLink = { blockedUrl = it }
        )
    }

    if (showSettings) {
        ReaderSettingsSheet(
            detectedCharset = archive.rootCharset,
            selectedCharset = charsetOverride,
            scriptsEnabled = scriptsEnabled,
            onCharset = { charsetOverride = it },
            onScripts = { scriptsEnabled = it },
            onDismiss = { showSettings = false }
        )
    }
}

@Composable
private fun PageSearchBar(
    query: String,
    active: Int,
    total: Int,
    onQuery: (String) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(start = 12.dp, end = 8.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            modifier = Modifier.weight(1f),
            singleLine = true,
            placeholder = { Text("在页面中查找") },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            supportingText = if (query.isNotEmpty()) {
                { Text(if (total > 0) "$active / $total" else "没有匹配") }
            } else null,
            keyboardOptions = KeyboardOptions.Default,
            keyboardActions = KeyboardActions(onSearch = { onNext() }),
            shape = RoundedCornerShape(20.dp)
        )
        IconButton(onClick = onPrevious, enabled = query.isNotBlank()) {
            Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = "上一个")
        }
        IconButton(onClick = onNext, enabled = query.isNotBlank()) {
            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "下一个")
        }
        IconButton(onClick = onClose) {
            Icon(Icons.Rounded.Close, contentDescription = "关闭")
        }
    }
}

@Composable
private fun ReaderStatusBar(
    charset: String,
    auto: Boolean,
    scriptsEnabled: Boolean,
    onSettings: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(Icons.Rounded.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
        Text(
            "离线",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.weight(1f))
        AssistChip(
            onClick = onSettings,
            label = { Text((if (auto) "自动 · " else "") + charset.uppercase(Locale.ROOT)) },
            leadingIcon = { Icon(Icons.Rounded.TextFields, contentDescription = null, modifier = Modifier.size(16.dp)) }
        )
        AssistChip(
            onClick = onSettings,
            label = { Text(if (scriptsEnabled) "脚本开" else "脚本关") },
            leadingIcon = { Icon(Icons.Rounded.Code, contentDescription = null, modifier = Modifier.size(16.dp)) }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderSettingsSheet(
    detectedCharset: String,
    selectedCharset: String?,
    scriptsEnabled: Boolean,
    onCharset: (String?) -> Unit,
    onScripts: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf(
        null to "自动检测 · $detectedCharset",
        "UTF-8" to "UTF-8",
        "GB18030" to "GB18030 / GBK",
        "Big5" to "Big5",
        "Shift_JIS" to "Shift_JIS",
        "EUC-KR" to "EUC-KR",
        "windows-1252" to "Windows-1252"
    )

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("阅读设置", style = MaterialTheme.typography.headlineSmall)
            Text(
                "自动模式按 MIME charset → BOM → HTML meta → 严格 UTF-8 的顺序判断；打开后统一重编码为 UTF-8 再交给 WebView。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider()
            Text("字符编码", style = MaterialTheme.typography.titleMedium)
            options.forEach { (value, label) ->
                val selected = selectedCharset == value
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { onCharset(value) }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (selected) Icons.Rounded.Check else Icons.Rounded.Language,
                        contentDescription = null,
                        tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(label, modifier = Modifier.weight(1f))
                }
            }

            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("允许存档脚本", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "默认关闭。即使开启，未收录在 MHTML 内的网络请求仍会被拦截。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = scriptsEnabled, onCheckedChange = onScripts)
            }
        }
    }
}

private fun sourceHost(url: String?): String? = runCatching {
    url?.let { Uri.parse(it).host ?: it.substringBefore('/').takeIf(String::isNotBlank) }
}.getOrNull()

private fun formatDate(millis: Long): String {
    if (millis <= 0L) return "未知时间"
    return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
    bytes < 1024L * 1024L * 1024L -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1024.0 / 1024.0)
    else -> String.format(Locale.getDefault(), "%.1f GB", bytes / 1024.0 / 1024.0 / 1024.0)
}
