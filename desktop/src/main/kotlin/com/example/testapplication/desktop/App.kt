package com.example.testapplication.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.testapplication.vocab.WordBook
import kotlinx.coroutines.launch
import java.awt.Cursor
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Rectangle
import java.io.File
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(
    state: GuessState,
    onDock: () -> Unit,
    onMinimize: (() -> Unit)? = null,
    onMaximize: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
    awtWindow: java.awt.Window? = null,
) {
    // 无标题栏（真透明）窗口没有原生边框，故通过 AWT 监听器自实现「拖拽移动 + 边缘/四角缩放」。
    DisposableEffect(awtWindow) {
        if (awtWindow == null) return@DisposableEffect onDispose {}

        // 边缘缩放热区宽度 / 最小窗口尺寸（单位与 AWT bounds 一致，macOS 上即逻辑点）
        val margin = 8
        val minW = 480
        val minH = 360
        // 命中边的位掩码：左1 右2 上4 下8；0 = 内部（移动）
        val left = 1; val right = 2; val top = 4; val bottom = 8
        fun zoneAt(x: Int, y: Int, w: Int, h: Int): Int {
            var z = 0
            if (x <= margin) z = z or left
            if (x >= w - margin) z = z or right
            if (y <= margin) z = z or top
            if (y >= h - margin) z = z or bottom
            return z
        }

        var zone = 0
        var startScreen = Point()        // 按下时鼠标屏幕坐标
        var startBounds = Rectangle()    // 按下时窗口 bounds（作为拖动锚点，避免抖动）
        var lastCursorType = -1          // 上次设置的光标类型，避免每次移动都改（少与 Compose 内部光标抢）

        val pressListener = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                zone = zoneAt(e.x, e.y, awtWindow.width, awtWindow.height)
                startScreen = Point(e.xOnScreen, e.yOnScreen)
                startBounds = awtWindow.bounds
            }
            override fun mouseExited(e: MouseEvent) {
                lastCursorType = -1
                awtWindow.cursor = Cursor.getDefaultCursor()
            }
        }
        val motionListener = object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                // 悬停时按所在热区切换缩放光标，内部恢复默认（仅在类型变化时设置，少与 Compose 内部光标抢）
                val type = when (zoneAt(e.x, e.y, awtWindow.width, awtWindow.height)) {
                    left -> Cursor.W_RESIZE_CURSOR
                    right -> Cursor.E_RESIZE_CURSOR
                    top -> Cursor.N_RESIZE_CURSOR
                    bottom -> Cursor.S_RESIZE_CURSOR
                    top or left -> Cursor.NW_RESIZE_CURSOR
                    top or right -> Cursor.NE_RESIZE_CURSOR
                    bottom or left -> Cursor.SW_RESIZE_CURSOR
                    bottom or right -> Cursor.SE_RESIZE_CURSOR
                    else -> Cursor.DEFAULT_CURSOR
                }
                if (type != lastCursorType) {
                    lastCursorType = type
                    awtWindow.cursor = Cursor.getPredefinedCursor(type)
                }
            }
            override fun mouseDragged(e: MouseEvent) {
                val dx = e.xOnScreen - startScreen.x
                val dy = e.yOnScreen - startScreen.y
                if (zone == 0) {
                    // 内部：整窗移动
                    awtWindow.setLocation(startBounds.x + dx, startBounds.y + dy)
                    return
                }
                // 边缘/四角：把命中的边按位移拉伸，最小尺寸时锁住对边
                var x = startBounds.x; var y = startBounds.y
                var w = startBounds.width; var h = startBounds.height
                if (zone and right != 0) w = startBounds.width + dx
                if (zone and bottom != 0) h = startBounds.height + dy
                if (zone and left != 0) { x = startBounds.x + dx; w = startBounds.width - dx }
                if (zone and top != 0) { y = startBounds.y + dy; h = startBounds.height - dy }
                if (w < minW) { if (zone and left != 0) x -= (minW - w); w = minW }
                if (h < minH) { if (zone and top != 0) y -= (minH - h); h = minH }
                awtWindow.setBounds(x, y, w, h)
            }
        }
        awtWindow.addMouseListener(pressListener)
        awtWindow.addMouseMotionListener(motionListener)
        onDispose {
            awtWindow.removeMouseListener(pressListener)
            awtWindow.removeMouseMotionListener(motionListener)
        }
    }
    MaterialTheme(colorScheme = darkColorScheme()) {
        val scope = rememberCoroutineScope()

        val words = state.words
        val currentWord = state.currentWord

        // ── 显示文本 ──
        val zs = state.sentences.getOrNull(state.sentenceIdx)
        val displayAccent = currentWord?.accent?.ifBlank { state.zpkMeta?.accent ?: "" } ?: ""
        val displayMeanCn = currentWord?.meanCn?.ifBlank { state.zpkMeta?.meanCn ?: "" } ?: ""
        val displayMeanEn = state.zpkMeta?.meanEn ?: ""
        val displaySentence = zs?.sentenceEn?.ifBlank { null }
            ?: currentWord?.sentence?.ifBlank { state.zpkMeta?.sentence ?: "" } ?: ""
        val displaySentenceTrans = zs?.translate?.ifBlank { null }
            ?: currentWord?.sentenceTrans?.ifBlank { state.zpkMeta?.sentenceTrans ?: "" } ?: ""
        val hasSentenceAudio = (zs != null) ||
            (state.zpkMeta?.sentenceAudio?.isNotBlank() == true &&
                state.zpkFiles?.containsKey(state.zpkMeta?.sentenceAudio) == true)
        val effectiveMastered = state.effectiveMastered

        // 朗读设置弹窗开关
        var showReadSettings by remember { mutableStateOf(false) }

        // ── 进度同步（与 Android 互通的 JSON）──
        val snackbar = remember { SnackbarHostState() }

        // 朗读被拦截（未连蓝牙）时的提示，用 tick 触发以便重复弹出
        LaunchedEffect(state.audioHintTick) {
            if (state.audioHintTick > 0) state.audioHint?.let { snackbar.showSnackbar(it) }
        }
        fun doExport() {
            val json = SyncJson.export(state.overrides, state.notRecognized)
            val dlg = FileDialog(null as Frame?, "导出学习进度", FileDialog.SAVE).apply {
                file = "vocab_progress.json"; isVisible = true
            }
            val dir = dlg.directory; val name = dlg.file
            if (dir != null && name != null) {
                val ok = runCatching { File(dir, name).writeText(json) }.isSuccess
                scope.launch {
                    snackbar.showSnackbar(if (ok) "已导出 ${state.overrides.size} 条斩状态到 $name" else "导出失败")
                }
            }
        }
        fun doImport() {
            val dlg = FileDialog(null as Frame?, "导入学习进度", FileDialog.LOAD).apply { isVisible = true }
            val dir = dlg.directory; val name = dlg.file
            if (dir != null && name != null) {
                val file = File(dir, name)
                val result = runCatching {
                    val text = file.readText(Charsets.UTF_8)
                    val map = SyncJson.importOverrides(text).toMutableMap()
                    state.overrides = map
                    state.prefs.saveOverrides(map)
                    val nr = SyncJson.importNotRecognized(text)
                    state.notRecognized = nr
                    state.prefs.saveNotRecognized(nr)
                    map.size
                }
                scope.launch {
                    snackbar.showSnackbar(
                        result.fold({ "已导入并覆盖 $it 条斩状态" }, {
                            val head = runCatching { file.readText().take(24).replace("\n", " ") }.getOrDefault("")
                            "导入失败：${it.message}｜文件开头: [$head]"
                        })
                    )
                }
            }
        }

        // ── 键盘焦点 ──
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { focus.requestFocus() }
        fun refocus() { runCatching { focus.requestFocus() } }

        // ── 朗读设置弹窗：朗读方式 + 遍数 + 自动朗读时显示翻译 ──
        if (showReadSettings) {
            AlertDialog(
                onDismissRequest = { showReadSettings = false; refocus() },
                title = { Text("朗读设置") },
                text = {
                    Column {
                        Text("朗读方式", fontWeight = FontWeight.Medium)
                        listOf(
                            READ_WORD_ONLY to "只读单词",
                            READ_SENTENCE_ONLY to "只读例句",
                            READ_BOTH_WORD_FIRST to "都读·先单词",
                            READ_BOTH_SENTENCE_FIRST to "都读·先例句",
                        ).forEach { (v, label) ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().clickable { state.updateReadMode(v) }
                            ) {
                                RadioButton(selected = state.readMode == v, onClick = { state.updateReadMode(v) })
                                Text(label)
                            }
                        }

                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                        Spacer(Modifier.height(8.dp))

                        Text("单词朗读遍数", fontWeight = FontWeight.Medium)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            (1..6).forEach { n ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.clickable { state.updateWordRepeat(n) }.padding(end = 2.dp)
                                ) {
                                    RadioButton(selected = state.wordRepeat == n, onClick = { state.updateWordRepeat(n) })
                                    Text("$n", fontSize = 14.sp)
                                }
                            }
                        }

                        Spacer(Modifier.height(4.dp))
                        Text("例句朗读遍数", fontWeight = FontWeight.Medium)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            (1..3).forEach { n ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.clickable { state.updateSentenceRepeat(n) }.padding(end = 4.dp)
                                ) {
                                    RadioButton(selected = state.sentenceRepeat == n, onClick = { state.updateSentenceRepeat(n) })
                                    Text("$n", fontSize = 14.sp)
                                }
                            }
                        }

                        Spacer(Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                                .clickable { state.updateShowTransWhileRead(!state.showTransWhileRead) }
                        ) {
                            Checkbox(
                                checked = state.showTransWhileRead,
                                onCheckedChange = { state.updateShowTransWhileRead(it) }
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("自动朗读时显示翻译")
                        }
                        Text(
                            "（遍数仅自动朗读 ▶ 时生效）",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showReadSettings = false; refocus() }) { Text("完成") }
                }
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(focus)
                .focusable()
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.DirectionLeft -> { state.goPrev(); true }
                        Key.DirectionRight -> { state.goNext(); true }
                        // ⬆️ 与空格、点击界面同逻辑：切换显示/隐藏翻译
                        Key.DirectionUp, Key.Spacebar -> { state.toggleReveal(); true }
                        Key.P -> { state.playWord(); true }
                        Key.DirectionDown -> { state.playSentence(); true }
                        else -> false
                    }
                }
        ) {
            Scaffold(
                snackbarHost = { SnackbarHost(snackbar) },
                topBar = {
                    GuessTopBar(
                        book = state.book, onBookChange = { state.book = it; refocus() },
                        filterMode = state.filterMode,
                        onFilterModeChange = { state.updateFilterMode(it); refocus() },
                        crossBookFilter = state.crossBookFilter,
                        onCrossBookFilterChange = { state.updateCrossBookFilter(it); refocus() },
                        readMode = state.readMode,
                        onOpenReadSettings = { showReadSettings = true },
                        autoPlaying = state.autoPlaying,
                        onToggleAuto = { state.toggleAuto(); refocus() },
                        autoTransparent = state.autoTransparent,
                        onAutoTransparentChange = { state.updateAutoTransparent(it); refocus() },
                        bluetoothOnly = state.bluetoothOnly,
                        onBluetoothOnlyChange = { state.updateBluetoothOnly(it); refocus() },
                        index = state.currentIndex, total = words.size, elapsed = state.elapsed,
                        onDock = onDock,
                        onMinimize = onMinimize,
                        onMaximize = onMaximize,
                        onClose = onClose,
                        onExport = { doExport(); refocus() },
                        onImport = { doImport(); refocus() },
                    )
                }
            ) { padding ->
                when {
                    state.loading -> Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    words.isEmpty() -> Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("没有单词")
                    }
                    else -> {
                        val word = currentWord!!
                        BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
                            val maxWpx = constraints.maxWidth.toFloat()
                            val maxHpx = constraints.maxHeight.toFloat()
                            Column(Modifier.fillMaxSize()) {
                                LinearProgressIndicator(
                                    progress = { (state.currentIndex + 1f) / words.size },
                                    modifier = Modifier.fillMaxWidth().height(4.dp),
                                )
                                Card(
                                    modifier = Modifier.fillMaxWidth().weight(1f).padding(16.dp),
                                    shape = RoundedCornerShape(16.dp),
                                ) {
                                    Box(
                                        Modifier.fillMaxSize().clickable { state.toggleReveal(); refocus() }
                                    ) {
                                        if (effectiveMastered) {
                                            Text(
                                                "斩", fontSize = 120.sp, fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.error.copy(alpha = 0.10f),
                                                modifier = Modifier.align(Alignment.TopEnd).padding(20.dp)
                                            )
                                        }
                                        if (!state.revealed) {
                                            // 未展开：单词/音标/例句 堆叠大字
                                            Column(
                                                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                                                    .padding(40.dp),
                                                verticalArrangement = Arrangement.Center,
                                                horizontalAlignment = Alignment.CenterHorizontally
                                            ) {
                                                AdaptiveWord(word.word, initial = 231f)
                                                if (displayAccent.isNotBlank()) {
                                                    Spacer(Modifier.height(16.dp))
                                                    Text(displayAccent, fontSize = 32.sp,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                                if (displaySentence.isNotBlank()) {
                                                    Spacer(Modifier.height(32.dp))
                                                    Text(
                                                        highlight(displaySentence, word.word, MaterialTheme.colorScheme.primary),
                                                        fontSize = 69.sp, lineHeight = 93.sp,
                                                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
                                                    )
                                                    if (hasSentenceAudio) {
                                                        Spacer(Modifier.height(12.dp))
                                                        TextButton(onClick = { state.playSentence(); refocus() }) {
                                                            Text("♪ 播放例句", fontSize = 18.sp)
                                                        }
                                                    }
                                                }
                                            }
                                        } else {
                                            // 已展开：单词作 50% 水印 + 内容叠加
                                            AdaptiveWord(word.word, initial = 273f, alpha = 0.5f,
                                                modifier = Modifier.align(Alignment.Center))
                                            Column(
                                                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                                                    .padding(40.dp),
                                                verticalArrangement = Arrangement.Center,
                                                horizontalAlignment = Alignment.CenterHorizontally
                                            ) {
                                                if (displayAccent.isNotBlank()) {
                                                    Text(displayAccent, fontSize = 30.sp,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                    Spacer(Modifier.height(20.dp))
                                                }
                                                if (displaySentence.isNotBlank()) {
                                                    Text(
                                                        highlight(displaySentence, word.word, MaterialTheme.colorScheme.primary),
                                                        fontSize = 44.sp, lineHeight = 59.sp,
                                                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
                                                    )
                                                    if (hasSentenceAudio) {
                                                        Spacer(Modifier.height(8.dp))
                                                        TextButton(onClick = { state.playSentence(); refocus() }) {
                                                            Text("♪ 播放例句", fontSize = 18.sp)
                                                        }
                                                    }
                                                }
                                                Spacer(Modifier.height(20.dp))
                                                HorizontalDivider(
                                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                                                    thickness = 2.dp
                                                )
                                                Spacer(Modifier.height(20.dp))
                                                if (displayMeanCn.isNotBlank()) {
                                                    Text(displayMeanCn, fontSize = 48.sp, lineHeight = 60.sp,
                                                        fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary,
                                                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                                                }
                                                if (displayMeanEn.isNotBlank()) {
                                                    Spacer(Modifier.height(14.dp))
                                                    Text(displayMeanEn, fontSize = 26.sp, lineHeight = 34.sp,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                                                }
                                                if (displaySentenceTrans.isNotBlank()) {
                                                    Spacer(Modifier.height(16.dp))
                                                    Text(displaySentenceTrans, fontSize = 30.sp, lineHeight = 40.sp,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                                                }
                                            }
                                        }
                                    }
                                }
                                // 底部：上一个/下一个
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    OutlinedButton(onClick = { state.goPrev(); refocus() },
                                        enabled = state.currentIndex > 0, modifier = Modifier.weight(1f)) {
                                        Text("上一个 (←)", fontSize = 16.sp)
                                    }
                                    Button(onClick = { state.goNext(); refocus() }, modifier = Modifier.weight(1f)) {
                                        Text("下一个 (→)", fontSize = 16.sp)
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                            }

                            // 悬浮可拖动「斩」按钮（左下角）
                            val btn = 64f
                            var zx by remember { mutableStateOf(20f) }
                            var zy by remember { mutableStateOf(0f) }
                            LaunchedEffect(maxHpx) { if (zy == 0f) zy = maxHpx - btn - 120f }
                            Box(
                                modifier = Modifier
                                    .offset { IntOffset(zx.roundToInt(), zy.roundToInt()) }
                                    .size(64.dp)
                                    .background(
                                        if (effectiveMastered) MaterialTheme.colorScheme.secondaryContainer
                                        else MaterialTheme.colorScheme.errorContainer, CircleShape
                                    )
                                    .pointerInput(Unit) {
                                        detectDragGestures { change, drag ->
                                            change.consume()
                                            zx = (zx + drag.x).coerceIn(0f, maxWpx - btn)
                                            zy = (zy + drag.y).coerceIn(0f, maxHpx - btn)
                                        }
                                    }
                                    .clickable { state.toggleMastered(); refocus() },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    if (effectiveMastered) "已斩" else "斩",
                                    fontSize = if (effectiveMastered) 15.sp else 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (effectiveMastered) MaterialTheme.colorScheme.onSecondaryContainer
                                    else MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GuessTopBar(
    book: WordBook, onBookChange: (WordBook) -> Unit,
    filterMode: Int, onFilterModeChange: (Int) -> Unit,
    crossBookFilter: Boolean, onCrossBookFilterChange: (Boolean) -> Unit,
    readMode: Int, onOpenReadSettings: () -> Unit,
    autoPlaying: Boolean, onToggleAuto: () -> Unit,
    autoTransparent: Boolean, onAutoTransparentChange: (Boolean) -> Unit,
    bluetoothOnly: Boolean, onBluetoothOnlyChange: (Boolean) -> Unit,
    index: Int, total: Int, elapsed: Int,
    onDock: () -> Unit,
    onMinimize: (() -> Unit)? = null,
    onMaximize: (() -> Unit)? = null,
    onClose: (() -> Unit)?,
    onExport: () -> Unit, onImport: () -> Unit,
) {
    TopAppBar(
        title = { Text("语境猜词  ${if (total == 0) 0 else index + 1}/$total") },
        actions = {
            // 词书下拉
            var bookMenu by remember { mutableStateOf(false) }
            Box {
                TextButton(onClick = { bookMenu = true }) { Text(book.displayName) }
                DropdownMenu(expanded = bookMenu, onDismissRequest = { bookMenu = false }) {
                    WordBook.entries.forEach { b ->
                        DropdownMenuItem(text = { Text(b.displayName) },
                            onClick = { onBookChange(b); bookMenu = false })
                    }
                }
            }
            // 过滤视图（4 选项）下拉
            var filterMenu by remember { mutableStateOf(false) }
            val filterLabel = when (normalizeFilterMode(filterMode)) {
                FILTER_MASTERED -> "已斩"
                FILTER_UNKNOWN  -> "不认识"
                else            -> "未斩"
            }
            Box {
                TextButton(onClick = { filterMenu = true }) { Text(filterLabel) }
                DropdownMenu(expanded = filterMenu, onDismissRequest = { filterMenu = false }) {
                    listOf(
                        FILTER_UNMASTERED to "未斩", FILTER_MASTERED to "已斩",
                        FILTER_UNKNOWN to "不认识",
                    ).forEach { (v, label) ->
                        DropdownMenuItem(text = { Text(label) },
                            onClick = { onFilterModeChange(v); filterMenu = false })
                    }
                }
            }
            // 跨词书过滤（去重）
            TextButton(onClick = { onCrossBookFilterChange(!crossBookFilter) }) {
                Text(
                    if (crossBookFilter) "已过滤" else "过滤",
                    color = if (crossBookFilter) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // 朗读设置（朗读方式 + 遍数 + 显示翻译，弹窗）
            val rmLabel = when (readMode) {
                READ_WORD_ONLY -> "读词"; READ_SENTENCE_ONLY -> "读句"
                READ_BOTH_SENTENCE_FIRST -> "句+词"; else -> "词+句"
            }
            TextButton(onClick = onOpenReadSettings) { Text("朗读·$rmLabel") }
            // 同步下拉（导出/导入 JSON，与 Android 互通）
            var syncMenu by remember { mutableStateOf(false) }
            Box {
                TextButton(onClick = { syncMenu = true }) { Text("同步") }
                DropdownMenu(expanded = syncMenu, onDismissRequest = { syncMenu = false }) {
                    DropdownMenuItem(text = { Text("导出进度…") },
                        onClick = { syncMenu = false; onExport() })
                    DropdownMenuItem(text = { Text("导入进度…") },
                        onClick = { syncMenu = false; onImport() })
                }
            }
            // 自动连续朗读 ▶/⏸（仅普通窗口在最前时运行）
            TextButton(onClick = onToggleAuto) {
                Text(
                    if (autoPlaying) "⏸" else "▶",
                    fontSize = 18.sp,
                    color = if (autoPlaying) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // 仅蓝牙耳机才朗读 开关（防外放）
            TextButton(onClick = { onBluetoothOnlyChange(!bluetoothOnly) }) {
                Text(
                    if (bluetoothOnly) "仅蓝牙·开" else "仅蓝牙·关",
                    color = if (bluetoothOnly) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // 鼠标离开自动透明 开关
            TextButton(onClick = { onAutoTransparentChange(!autoTransparent) }) {
                Text(
                    if (autoTransparent) "透明·开" else "透明·关",
                    color = if (autoTransparent) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // 贴底（迷你悬浮条）
            TextButton(onClick = onDock) { Text("贴底") }
            val m = elapsed / 60; val s = elapsed % 60
            Text("$m:${"%02d".format(s)}", style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(end = 8.dp))
            // 最小化按钮（无标题栏模式下代替系统黄色按钮，一键收到 Dock）
            if (onMinimize != null) {
                TextButton(onClick = onMinimize) {
                    Text("—", color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
                }
            }
            // 最大化/还原按钮（无标题栏模式下代替系统绿色按钮，点击铺满屏幕，再点还原）
            if (onMaximize != null) {
                TextButton(onClick = onMaximize) {
                    Text("▢", color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
                }
            }
            // 关闭按钮（无标题栏模式下代替系统红绿灯）
            if (onClose != null) {
                TextButton(onClick = onClose) {
                    Text("✕", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            }
        }
    )
}

/** 单词大字：从 initial 起，单行放不下就缩小 */
@Composable
internal fun AdaptiveWord(word: String, initial: Float, alpha: Float = 1f, modifier: Modifier = Modifier) {
    var size by remember(word, initial) { mutableStateOf(initial) }
    var measured by remember(word, initial) { mutableStateOf(false) }
    Text(
        text = word,
        fontSize = TextUnit(size, TextUnitType.Sp),
        fontWeight = FontWeight.ExtraBold,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Clip,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = alpha),
        modifier = modifier.fillMaxWidth().alpha(if (measured) 1f else 0f),
        onTextLayout = { r ->
            if (r.hasVisualOverflow && size > 24f) size *= 0.88f else if (!measured) measured = true
        }
    )
}

/** 例句中高亮目标单词（含常见词形变化） */
internal fun highlight(sentence: String, word: String, color: Color) = buildAnnotatedString {
    val ls = sentence.lowercase(); val lw = word.lowercase()
    val variants = mutableListOf(lw)
    if (lw.length >= 2) {
        variants += listOf(lw + "s", lw + "es", lw + "ed", lw + "d", lw + "ing", lw + "er", lw + "est", lw + "ly")
        if (lw.endsWith("e")) { val st = lw.dropLast(1); variants += listOf(st + "ing", st + "ed") }
        if (lw.endsWith("y")) { val st = lw.dropLast(1); variants += listOf(st + "ied", st + "ies") }
    }
    data class M(val start: Int, val len: Int)
    val matches = mutableListOf<M>()
    for (v in variants.sortedByDescending { it.length }) {
        var from = 0
        while (true) {
            val i = ls.indexOf(v, from); if (i < 0) break
            val before = if (i > 0) ls[i - 1] else ' '
            val after = if (i + v.length < ls.length) ls[i + v.length] else ' '
            if (!before.isLetter() && !after.isLetter() &&
                matches.none { i < it.start + it.len && i + v.length > it.start }) {
                matches += M(i, v.length)
            }
            from = i + 1
        }
    }
    if (matches.isEmpty()) { append(sentence); return@buildAnnotatedString }
    var pos = 0
    for (m in matches.sortedBy { it.start }) {
        if (m.start > pos) append(sentence.substring(pos, m.start))
        withStyle(SpanStyle(fontWeight = FontWeight.ExtraBold, color = color)) {
            append(sentence.substring(m.start, m.start + m.len))
        }
        pos = m.start + m.len
    }
    if (pos < sentence.length) append(sentence.substring(pos))
}
