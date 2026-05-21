package com.example.testapplication.vocab

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class WordListActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            com.example.testapplication.ui.theme.TestApplicationTheme {
                WordListScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WordListScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { WordRepository.getInstance(context) }

    // 列表 ♪：与闪卡相同——优先 zpk 单词 MP3（meta.word_audio / uk_*.mp3），缺省时用系统 TTS
    val listPlayer = remember { MediaPlayer() }
    val listPreviewCache = remember { File(context.cacheDir, "word_list_preview.mp3") }
    val listAudioAttrs = remember {
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }
    DisposableEffect(Unit) {
        onDispose {
            try { listPlayer.release() } catch (_: Exception) {}
        }
    }

    val ttsRef = remember { mutableStateOf<TextToSpeech?>(null) }
    DisposableEffect(context) {
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                engine?.setLanguage(Locale.US)
            }
        }
        ttsRef.value = engine
        onDispose { engine?.shutdown() }
    }

    fun playWordPreview(word: Word) {
        scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                repository.readZpk(word.topicId)?.let { wordAudioBytesFromZpk(it) }
            }
            val prepared = if (bytes != null && bytes.isNotEmpty()) {
                withContext(Dispatchers.IO) {
                    try {
                        listPlayer.reset()
                        listPlayer.setAudioAttributes(listAudioAttrs)
                        listPreviewCache.writeBytes(bytes)
                        listPlayer.setDataSource(listPreviewCache.absolutePath)
                        listPlayer.prepare()
                        true
                    } catch (_: Exception) {
                        false
                    }
                }
            } else {
                false
            }

            if (prepared) {
                withContext(Dispatchers.Main) {
                    try {
                        listPlayer.start()
                    } catch (_: Exception) {
                        ttsRef.value?.speak(word.word, TextToSpeech.QUEUE_FLUSH, null, null)
                    }
                }
            } else {
                ttsRef.value?.speak(word.word, TextToSpeech.QUEUE_FLUSH, null, null)
            }
        }
    }

    val loadState       by repository.loadState.collectAsState()
    val syncState       by repository.syncState.collectAsState()
    val overrides       by repository.overrides.collectAsState()
    val readUnmastered  by repository.readTopicIdsUnmastered.collectAsState()
    val readMastered    by repository.readTopicIdsMastered.collectAsState()
    val crossBookCounts    by repository.crossBookCounts.collectAsState()
    val bookTopicIds       by repository.bookTopicIds.collectAsState()
    val globalFilteredCounts by repository.globalFilteredCounts.collectAsState()

    val prefs = remember { MMKV.mmkvWithID("word_list_prefs") }
    var selectedBook   by remember {
        val savedId = prefs.decodeInt("selected_book_id", WordBook.ZHONGKAO.id)
        val book = WordBook.entries.firstOrNull { it.id == savedId } ?: WordBook.ZHONGKAO
        mutableStateOf(book)
    }
    LaunchedEffect(selectedBook) { prefs.encode("selected_book_id", selectedBook.id) }
    var selectedTab    by remember { mutableIntStateOf(0) }
    var searchQuery    by remember { mutableStateOf("") }
    var showBookPickDialog by remember { mutableStateOf(false) }
    var editMode           by remember { mutableStateOf(false) }
    var filterEnabled      by remember { mutableStateOf(prefs.decodeBool("cross_book_filter", false)) }
    LaunchedEffect(filterEnabled) { prefs.encode("cross_book_filter", filterEnabled) }
    // 切换 Tab 或词书时退出编辑模式
    LaunchedEffect(selectedTab, selectedBook) { editMode = false }

    // 过滤开启时计算全词书去重总数（overrides 变化时也刷新）
    LaunchedEffect(filterEnabled, overrides) {
        if (filterEnabled) repository.refreshGlobalFilteredCounts()
    }

    // 加载词书（切换词书时重新加载）
    LaunchedEffect(selectedBook) {
        repository.loadWords(selectedBook)
    }

    // 当前词书需要排除的 topicId 集合：所有排在当前词书之前（更高优先级）的词书的并集
    // 优先级见 WordBook 枚举顺序：中考 > 高考 > 四级 > 六级 > 雅思
    val excludedTopicIds = remember(selectedBook, bookTopicIds, filterEnabled) {
        if (!filterEnabled) emptySet()
        else WordBook.entries
            .takeWhile { it != selectedBook }
            .flatMap { bookTopicIds[it] ?: emptySet() }
            .toSet()
    }

    val allWords = (loadState as? LoadState.Success)?.words ?: emptyList()
    val unmastered = remember(allWords, overrides, excludedTopicIds) {
        allWords.filter {
            !repository.effectiveMastered(it.topicId, it.masteredInDb, overrides) &&
            it.topicId !in excludedTopicIds
        }
    }
    val mastered = remember(allWords, overrides, excludedTopicIds) {
        allWords.filter {
            repository.effectiveMastered(it.topicId, it.masteredInDb, overrides) &&
            it.topicId !in excludedTopicIds
        }
    }
    val baseList = if (selectedTab == 0) unmastered else mastered
    val displayList = remember(baseList, searchQuery) {
        if (searchQuery.isBlank()) baseList
        else baseList.filter {
            it.word.contains(searchQuery, ignoreCase = true) || it.meanCn.contains(searchQuery)
        }
    }

    // ── 词书选择对话框 ──────────────────────────────────────────
    if (showBookPickDialog) {
        AlertDialog(
            onDismissRequest = { showBookPickDialog = false },
            title = { Text("选择词书") },
            text = {
                Column {
                    WordBook.entries.forEach { book ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (selectedBook != book) {
                                        selectedBook = book
                                        selectedTab  = 0
                                        searchQuery  = ""
                                    }
                                    showBookPickDialog = false
                                }
                                .padding(vertical = 10.dp)
                        ) {
                            RadioButton(
                                selected = selectedBook == book,
                                onClick = {
                                    if (selectedBook != book) {
                                        selectedBook = book
                                        selectedTab  = 0
                                        searchQuery  = ""
                                    }
                                    showBookPickDialog = false
                                }
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(book.displayName)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showBookPickDialog = false }) { Text("取消") }
            }
        )
    }

    // 从设置页重载词库后自动刷新
    LaunchedEffect(syncState) {
        when (syncState) {
            is SyncState.Success -> {
                repository.loadWords(selectedBook, forceReload = true)
                repository.resetSyncState()
            }
            else -> {}
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("背单词") },
                navigationIcon = {
                    IconButton(onClick = { (context as? WordListActivity)?.finish() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        context.startActivity(Intent(context, SettingsActivity::class.java))
                    }) {
                        Icon(Icons.Filled.Settings, contentDescription = "设置")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {

            // ── 词书选择 + 过滤开关 ─────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = true,
                    onClick  = { showBookPickDialog = true },
                    label    = { Text(selectedBook.displayName) }
                )
                Spacer(Modifier.weight(1f))
                FilterChip(
                    selected = filterEnabled,
                    onClick  = { filterEnabled = !filterEnabled },
                    label = {
                        if (filterEnabled && globalFilteredCounts != null) {
                            val (u, m) = globalFilteredCounts!!
                            Text("已过滤 $u/$m")
                        } else {
                            Text(if (filterEnabled) "已过滤" else "过滤")
                        }
                    }
                )
            }

            // ── 搜索框 ──────────────────────────────────────────
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("搜索单词或释义…") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 2.dp)
            )

            // ── 未斩 / 已斩 Tab + 编辑按钮 ──────────────────────
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                TabRow(
                    selectedTabIndex = selectedTab,
                    modifier = Modifier.weight(1f)
                ) {
                    Tab(selected = selectedTab == 0, onClick = { selectedTab = 0; searchQuery = "" },
                        text = { Text("未斩（${unmastered.size}）") })
                    Tab(selected = selectedTab == 1, onClick = { selectedTab = 1; searchQuery = "" },
                        text = { Text("已斩（${mastered.size}）") })
                }
                TextButton(
                    onClick = { editMode = !editMode },
                    modifier = Modifier.padding(horizontal = 4.dp)
                ) {
                    Text(if (editMode) "完成" else "编辑")
                }
            }

            // ── 内容区 ──────────────────────────────────────────
            when (val state = loadState) {
                is LoadState.Idle, is LoadState.Loading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(12.dp))
                            Text("正在读取 ${selectedBook.displayName} 词书…")
                        }
                    }
                }
                is LoadState.Error -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(24.dp)
                        ) {
                            Text("加载失败", style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.height(8.dp))
                            Text(state.message, style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = { scope.launch { repository.loadWords(selectedBook) } }) {
                                Text("重试")
                            }
                        }
                    }
                }
                is LoadState.Success -> {
                    // 当前 tab 对应的已读集合（未斩 / 已斩 分桶）
                    val readTopicIds = if (selectedTab == 0) readUnmastered else readMastered
                    if (displayList.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(if (searchQuery.isBlank()) "暂无单词" else "未找到匹配单词")
                        }
                    } else {
                        val listState = rememberLazyListState()
                        // 有已读单词时，滚动到第一个未读单词
                        LaunchedEffect(displayList, readTopicIds) {
                            if (readTopicIds.isNotEmpty()) {
                                val firstUnreadIndex = displayList.indexOfFirst { it.topicId !in readTopicIds }
                                if (firstUnreadIndex > 0) {
                                    listState.scrollToItem(firstUnreadIndex)
                                }
                            }
                        }
                        Box(Modifier.fillMaxSize()) {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(end = 28.dp),
                                state = listState,
                            ) {
                                itemsIndexed(displayList) { idx, word ->
                                    val indexInBase = baseList.indexOf(word)
                                    val isRead = readTopicIds.contains(word.topicId)
                                    val crossBookCount = if (selectedTab == 0)
                                        crossBookCounts[word.topicId] ?: 1 else 1
                                    WordListItem(
                                        word = word, index = idx + 1, isRead = isRead,
                                        crossBookCount = crossBookCount,
                                        editMode = editMode,
                                        isUnmastered = (selectedTab == 0),
                                        onToggleMastered = {
                                            val currentEffective = repository.effectiveMastered(
                                                word.topicId, word.masteredInDb, overrides)
                                            scope.launch {
                                                repository.toggleMastered(word.topicId, currentEffective)
                                            }
                                            playWordPreview(word)
                                        },
                                        onRevealTranslationAndPlay = { playWordPreview(word) },
                                        onWordClick = {
                                            context.startActivity(
                                                Intent(context, FlashCardActivity::class.java).apply {
                                                    putExtra(FlashCardActivity.EXTRA_FILTER,
                                                        if (selectedTab == 0) "unmastered" else "mastered")
                                                    putExtra(FlashCardActivity.EXTRA_START_INDEX,
                                                        indexInBase.coerceAtLeast(0))
                                                    putExtra(FlashCardActivity.EXTRA_BOOK_ID, selectedBook.id)
                                                    putExtra(FlashCardActivity.EXTRA_CROSS_BOOK_FILTER, filterEnabled)
                                                }
                                            )
                                        }
                                    )
                                    HorizontalDivider()
                                }
                            }
                            FloatingAlphabetSidebar(
                                displayList = displayList,
                                listState = listState,
                                scope = scope,
                                modifier = Modifier.align(Alignment.CenterEnd),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 右侧索引条：固定 A–Z + #；首字母按不区分大小写，非字母归入 # */
private val ALPHABET_INDEX_LETTERS: List<Char> = ('A'..'Z').toList() + '#'

private fun bucketForWord(word: String): Char {
    val first = word.trimStart().firstOrNull() ?: return '#'
    val u = first.uppercaseChar()
    return if (u in 'A'..'Z') u else '#'
}

private fun buildLetterToFirstIndex(words: List<Word>): Map<Char, Int> {
    val map = LinkedHashMap<Char, Int>()
    words.forEachIndexed { idx, w ->
        val b = bucketForWord(w.word)
        if (!map.containsKey(b)) map[b] = idx
    }
    return map
}

/** 悬浮半透明字母索引：拖拽连续跳转；高亮与列表首可见词首字母联动 */
@Composable
private fun FloatingAlphabetSidebar(
    displayList: List<Word>,
    listState: LazyListState,
    scope: kotlinx.coroutines.CoroutineScope,
    modifier: Modifier = Modifier,
) {
    val letterToFirstIndex = remember(displayList) { buildLetterToFirstIndex(displayList) }
    val highlightedLetter by remember(displayList, listState) {
        derivedStateOf {
            val idx = listState.layoutInfo.visibleItemsInfo.firstOrNull()?.index
                ?: return@derivedStateOf null
            displayList.getOrNull(idx)?.word?.let { bucketForWord(it) }
        }
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(28.dp)
            .padding(vertical = 2.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(24.dp)
                .background(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.52f),
                    shape = RoundedCornerShape(12.dp),
                )
                .pointerInput(letterToFirstIndex, listState, scope, displayList) {
                    fun scrollAt(py: Float) {
                        val h = size.height.toFloat()
                        if (h <= 0f) return
                        val seg = ALPHABET_INDEX_LETTERS.size
                        val letterIdx =
                            (py / h * seg).toInt().coerceIn(0, seg - 1)
                        val ch = ALPHABET_INDEX_LETTERS[letterIdx]
                        val wi = letterToFirstIndex[ch] ?: return
                        scope.launch { listState.scrollToItem(wi) }
                    }
                    detectTapGestures(onTap = { off -> scrollAt(off.y) })
                }
                .pointerInput(letterToFirstIndex, listState, scope, displayList) {
                    fun scrollAt(py: Float) {
                        val h = size.height.toFloat()
                        if (h <= 0f) return
                        val seg = ALPHABET_INDEX_LETTERS.size
                        val letterIdx =
                            (py / h * seg).toInt().coerceIn(0, seg - 1)
                        val ch = ALPHABET_INDEX_LETTERS[letterIdx]
                        val wi = letterToFirstIndex[ch] ?: return
                        scope.launch { listState.scrollToItem(wi) }
                    }
                    detectVerticalDragGestures { change, _ ->
                        scrollAt(change.position.y)
                        change.consume()
                    }
                },
        ) {
            ALPHABET_INDEX_LETTERS.forEach { ch ->
                val hasWords = letterToFirstIndex.containsKey(ch)
                val hi = hasWords && highlightedLetter != null && ch == highlightedLetter
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = ch.toString(),
                        fontSize = when {
                            hi -> 12.sp
                            !hasWords -> 9.sp
                            else -> 10.sp
                        },
                        fontWeight = if (hi) FontWeight.Bold else FontWeight.Normal,
                        color = when {
                            !hasWords ->
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.20f)
                            hi -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                        },
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** 列表左侧单词：基准字号约 [maxFontSp]，过长时在 [minFontSp..maxFontSp] 内缩小，单行完整显示、不省略。 */
@Composable
private fun WordCellScaledText(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    maxFontSp: Int = 21,
    minFontSp: Int = 8,
) {
    BoxWithConstraints(modifier = modifier) {
        val maxW = constraints.maxWidth
        if (maxW <= 0 || maxW == Constraints.Infinity) {
            Text(
                text = text,
                fontSize = maxFontSp.sp,
                fontWeight = FontWeight.SemiBold,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Visible,
                softWrap = false,
            )
            return@BoxWithConstraints
        }

        val measurer = rememberTextMeasurer()
        val annotated = AnnotatedString(text)

        var low = minFontSp
        var high = maxFontSp
        var best = minFontSp
        while (low <= high) {
            val mid = (low + high) / 2
            val layoutResult = measurer.measure(
                text = annotated,
                style = TextStyle(
                    fontSize = mid.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                constraints = Constraints(maxWidth = maxW),
                maxLines = 1,
            )
            if (layoutResult.size.width <= maxW) {
                best = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }

        Text(
            text = text,
            fontSize = best.sp,
            fontWeight = FontWeight.SemiBold,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Visible,
            softWrap = false,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun WordListItem(
    word: Word, index: Int, isRead: Boolean,
    crossBookCount: Int = 1,
    /** 编辑模式：右侧显示「斩」按钮 */
    editMode: Boolean = false,
    /** true = 当前条目在未斩列表；false = 已斩列表 */
    isUnmastered: Boolean = true,
    /** 点击斩按钮：切换掌握状态 */
    onToggleMastered: () -> Unit = {},
    /** 点击释义区域：首次显示翻译并播报；再次点击可重复播报 */
    onRevealTranslationAndPlay: () -> Unit,
    /** 点击左侧单词区域：进入闪卡 */
    onWordClick: () -> Unit,
) {
    var translationVisible by remember(word.topicId) { mutableStateOf(false) }

    val wordColor = when (crossBookCount) {
        5    -> Color(0xFFFF5252) // 5 本词书都出现 → 亮红
        4    -> Color(0xFFE040FB) // 出现 4 次 → 亮紫
        3    -> Color(0xFFFFD740) // 出现 3 次 → 琥珀黄
        2    -> Color(0xFF69F0AE) // 出现 2 次 → 薄荷绿
        else -> Color.Unspecified // 仅当前词书 → 默认白色
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 左侧宽约条目 1/5（weight 1:4）：点击进闪卡
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onWordClick),
        ) {
            if (isRead) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            color = Color(0xFF69F0AE),
                            shape = RoundedCornerShape(2.dp)
                        )
                )
                Spacer(Modifier.width(6.dp))
            }
            WordCellScaledText(
                text = word.word,
                color = wordColor,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 2.dp)
            )
        }
        // 右侧：默认隐藏释义；点击显示翻译（最多两行）并播报
        // 序号悬浮在翻译上方（70% 透明度），编辑模式的斩按钮叠在序号上（50% 透明度）
        Box(
            modifier = Modifier
                .weight(4f)
                .clickable(onClick = {
                    translationVisible = true
                    onRevealTranslationAndPlay()
                })
                .heightIn(min = 64.dp)
        ) {
            if (translationVisible) {
                Text(
                    text = word.meanCn,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.CenterStart),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = "$index",
                fontSize = 9.sp,
                lineHeight = 10.sp,
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.70f),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
            )
            if (editMode) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .clickable(onClick = onToggleMastered)
                        .padding(horizontal = 5.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "斩",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = (if (isUnmastered)
                            MaterialTheme.colorScheme.error
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant).copy(alpha = 0.50f),
                        textDecoration = if (isUnmastered) null else TextDecoration.LineThrough,
                    )
                }
            }
        }
    }
}
