package com.example.testapplication.vocab

import android.content.pm.ActivityInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

class ContextGuessActivity : ComponentActivity() {
    companion object {
        const val EXTRA_FILTER            = "filter_type"
        const val EXTRA_START_INDEX       = "start_index"
        const val EXTRA_BOOK_ID           = "book_id"
        const val EXTRA_CROSS_BOOK_FILTER = "cross_book_filter"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()

        // 平板（最小宽度 >= 600dp）跟随系统旋转；手机锁竖屏
        requestedOrientation = if (resources.configuration.smallestScreenWidthDp >= 600) {
            ActivityInfo.SCREEN_ORIENTATION_USER
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }

        val filterType      = intent.getStringExtra(EXTRA_FILTER) ?: "unmastered"
        val startIndex      = intent.getIntExtra(EXTRA_START_INDEX, 0)
        val bookId          = intent.getIntExtra(EXTRA_BOOK_ID, WordBook.ZHONGKAO.id)
        val crossBookFilter = intent.getBooleanExtra(EXTRA_CROSS_BOOK_FILTER, false)
        setContent {
            com.example.testapplication.ui.theme.TestApplicationTheme {
                ContextGuessScreen(filterType, startIndex, bookId, crossBookFilter)
            }
        }
    }
}

// 朗读模式
private const val READ_WORD_ONLY         = 0  // 只读单词
private const val READ_SENTENCE_ONLY     = 1  // 只读例句
private const val READ_BOTH_WORD_FIRST   = 2  // 都读·先读单词
private const val READ_BOTH_SENTENCE_FIRST = 3 // 都读·先读例句

// ── 主界面 ───────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContextGuessScreen(
    filterType: String,
    startIndex: Int,
    bookId: Int,
    crossBookFilter: Boolean = false,
) {
    val context = LocalContext.current
    val repository = remember { WordRepository.getInstance(context) }
    val overrides       by repository.overrides.collectAsState()
    val crossBookCounts by repository.crossBookCounts.collectAsState()
    val bookTopicIds    by repository.bookTopicIds.collectAsState()
    val prefs = remember { MMKV.mmkvWithID("context_guess_prefs") }
    val scope = rememberCoroutineScope()

    // 朗读模式（持久化）：0=只读单词 1=只读例句 2=都读·先单词 3=都读·先例句
    var readMode by remember { mutableIntStateOf(prefs.decodeInt("read_mode", READ_BOTH_WORD_FIRST)) }
    LaunchedEffect(readMode) { prefs.encode("read_mode", readMode) }
    var showReadModeDialog by remember { mutableStateOf(false) }

    // 确保词书已加载
    LaunchedEffect(bookId) {
        val book = WordBook.entries.firstOrNull { it.id == bookId } ?: WordBook.ZHONGKAO
        if (repository.currentBook?.id != bookId) {
            scope.launch { repository.loadWords(book) }
        }
    }

    // 跨词书过滤
    val currentBook = WordBook.entries.firstOrNull { it.id == bookId } ?: WordBook.ZHONGKAO
    val excludedTopicIds = remember(currentBook, bookTopicIds, crossBookFilter) {
        if (!crossBookFilter) emptySet()
        else WordBook.entries
            .takeWhile { it != currentBook }
            .flatMap { bookTopicIds[it] ?: emptySet() }
            .toSet()
    }

    val loadStateVal by repository.loadState.collectAsState()
    val allWords = remember(loadStateVal, overrides, excludedTopicIds) {
        val state = loadStateVal
        if (state is LoadState.Success && repository.currentBook?.id == bookId) {
            val ov = overrides
            when (filterType) {
                "mastered" -> state.words.filter {
                    repository.effectiveMastered(it.topicId, it.masteredInDb, ov) &&
                        it.topicId !in excludedTopicIds
                }
                else -> state.words.filter {
                    !repository.effectiveMastered(it.topicId, it.masteredInDb, ov) &&
                        it.topicId !in excludedTopicIds
                }
            }
        } else emptyList()
    }

    // 当前单词索引（纯浏览模式）：进度按 词书+类型 持久化，重进自动续读
    val progressKey = "progress_idx_${bookId}_${filterType}"
    var currentIndex by remember {
        mutableIntStateOf(prefs.decodeInt(progressKey, startIndex).coerceAtLeast(0))
    }
    // 词表加载完成后把索引夹到合法范围
    LaunchedEffect(allWords.size) {
        if (allWords.isNotEmpty()) currentIndex = currentIndex.coerceIn(0, allWords.size - 1)
    }
    // 索引变化时保存进度
    LaunchedEffect(currentIndex) {
        if (allWords.isNotEmpty()) prefs.encode(progressKey, currentIndex)
    }
    val currentWord: Word? = allWords.getOrNull(currentIndex)
    val totalCount = allWords.size
    val currentPos = currentIndex.coerceIn(0, (allWords.size - 1).coerceAtLeast(0))

    // 是否显示翻译（点击卡片来回切换；切换单词时自动隐藏）
    var revealed by remember { mutableStateOf(false) }

    // ── zpk 数据 ──
    var zpkFiles by remember { mutableStateOf<Map<String, ByteArray>?>(null) }
    var zpkMeta  by remember { mutableStateOf<ZpkMeta?>(null) }
    // 多例句轮播
    val zpkSentencesState               = remember { mutableStateOf<List<ZpkSentence>>(emptyList()) }
    val currentSentenceDisplayIndexState = remember { mutableIntStateOf(0) }

    // 累积计时
    var elapsedSeconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000L)
            elapsedSeconds++
        }
    }

    // ── 音频 ──
    val mediaAudioAttributes = remember {
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }
    val player = remember { MediaPlayer() }
    val cacheFile = remember { File(context.cacheDir, "ctx_guess_audio.mp3") }

    val ttsRef = remember { mutableStateOf<TextToSpeech?>(null) }
    var ttsReady by remember { mutableStateOf(false) }
    DisposableEffect(context) {
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                when (engine?.setLanguage(Locale.US)) {
                    TextToSpeech.LANG_MISSING_DATA,
                    TextToSpeech.LANG_NOT_SUPPORTED -> {}
                    else -> { ttsRef.value = engine; ttsReady = true }
                }
            }
        }
        onDispose { engine?.shutdown(); ttsRef.value = null }
    }
    DisposableEffect(Unit) { onDispose { player.release() } }

    fun playBytes(bytes: ByteArray, onComplete: (() -> Unit)? = null) {
        scope.launch {
            val prepared = withContext(Dispatchers.IO) {
                try {
                    player.reset()
                    player.setAudioAttributes(mediaAudioAttributes)
                    cacheFile.writeBytes(bytes)
                    player.setDataSource(cacheFile.absolutePath)
                    player.prepare()
                    true
                } catch (_: Exception) { false }
            }
            if (!prepared) { onComplete?.invoke(); return@launch }
            withContext(Dispatchers.Main) {
                player.setOnCompletionListener { onComplete?.invoke() }
                player.start()
            }
        }
    }

    /** 朗读例句音频（手动点击 or 自动链式调用） */
    fun speakSentence(onComplete: (() -> Unit)? = null) {
        val audioKey = zpkSentencesState.value
            .getOrNull(currentSentenceDisplayIndexState.intValue)?.audio
            ?: zpkMeta?.sentenceAudio?.takeIf { it.isNotBlank() }
            ?: run { onComplete?.invoke(); return }
        val bytes = zpkFiles?.get(audioKey) ?: run { onComplete?.invoke(); return }
        playBytes(bytes, onComplete)
    }

    /** 朗读单词音频（zpk 优先，TTS 兜底） */
    fun speakWord(onComplete: (() -> Unit)? = null) {
        val word = currentWord ?: run { onComplete?.invoke(); return }
        scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                val files = zpkFiles ?: repository.readZpk(word.topicId)
                files?.let { wordAudioBytesFromZpk(it) }
            }
            if (bytes != null) {
                playBytes(bytes, onComplete)
            } else {
                ttsRef.value?.speak(word.word, TextToSpeech.QUEUE_FLUSH, null, "word")
                if (onComplete != null) {
                    kotlinx.coroutines.delay(800L)
                    onComplete()
                }
            }
        }
    }

    /** 按朗读模式自动播放；链式时校验仍停留在同一词，避免翻页后串音 */
    fun autoPlay() {
        val topicId = currentWord?.topicId ?: return
        when (readMode) {
            READ_WORD_ONLY     -> speakWord()
            READ_SENTENCE_ONLY -> speakSentence()
            READ_BOTH_SENTENCE_FIRST -> speakSentence {
                if (currentWord?.topicId == topicId) speakWord()
            }
            else /* READ_BOTH_WORD_FIRST */ -> speakWord {
                if (currentWord?.topicId == topicId) speakSentence()
            }
        }
    }

    // ── 导航逻辑（纯浏览：上一个/下一个；切词时隐藏翻译并停止朗读）──

    fun goPrev() {
        if (currentIndex <= 0) return
        revealed = false
        try { player.reset() } catch (_: Exception) {}
        currentIndex--
    }

    fun goNext() {
        revealed = false
        try { player.reset() } catch (_: Exception) {}
        currentIndex = if (currentIndex + 1 >= allWords.size) 0 else currentIndex + 1
    }

    // ── 加载 zpk ──
    val wordKey = currentWord?.topicId
    LaunchedEffect(wordKey) {
        if (currentWord == null) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val files = repository.readZpk(currentWord.topicId)
            zpkFiles = files
            zpkMeta = files?.let { parseZpkMeta(it) }
            zpkSentencesState.value = files?.let { parseZpkSentences(it) } ?: emptyList()
        }
        // 多例句轮播
        val sentences = zpkSentencesState.value
        if (sentences.isNotEmpty()) {
            val mmkvKey = "sentence_idx_${currentWord.topicId}"
            val flashPrefs = MMKV.mmkvWithID("flashcard_prefs")
            val storedIdx = flashPrefs.decodeInt(mmkvKey, 0).coerceIn(0, sentences.size - 1)
            currentSentenceDisplayIndexState.intValue = storedIdx
            // 推进索引（和闪卡共用 MMKV key）
            flashPrefs.encode(mmkvKey, (storedIdx + 1) % sentences.size)
        } else {
            currentSentenceDisplayIndexState.intValue = 0
        }
        // 按朗读模式自动播放
        autoPlay()
    }

    // 当前展示的文字
    val displayAccent = currentWord?.accent?.ifBlank { zpkMeta?.accent ?: "" } ?: ""
    val displayMeanCn = currentWord?.meanCn?.ifBlank { zpkMeta?.meanCn ?: "" } ?: ""
    val displayMeanEn = zpkMeta?.meanEn ?: ""
    val currentZpkSentence = zpkSentencesState.value.getOrNull(currentSentenceDisplayIndexState.intValue)
    val displaySentence = currentZpkSentence?.sentenceEn?.ifBlank { null }
        ?: currentWord?.sentence?.ifBlank { zpkMeta?.sentence ?: "" } ?: ""
    val displaySentenceTrans = currentZpkSentence?.translate?.ifBlank { null }
        ?: currentWord?.sentenceTrans?.ifBlank { zpkMeta?.sentenceTrans ?: "" } ?: ""
    val hasSentenceAudio = currentZpkSentence != null
        || (zpkMeta?.sentenceAudio?.isNotBlank() == true
            && zpkFiles?.containsKey(zpkMeta?.sentenceAudio) == true)

    // ── 手势：右滑=上一个，左滑=下一个 ──
    val swipeDrag = remember { mutableFloatStateOf(0f) }

    // ── 鼠标滚轮翻页（仅平板）──
    // 向上滚=上一个；向下滚=下一个。
    // 内容较长时滚轮先用于浏览内容，滚到边界再多滚一下（剩余未消费量）才翻页。
    val tabletScrollState = rememberScrollState()
    // 切换单词 / 展开状态时，内容回到顶部
    LaunchedEffect(wordKey, revealed) { tabletScrollState.scrollTo(0) }
    // 始终捕获最新状态/动作；返回 true 表示已消费（拦截为翻页）
    val onWheel by rememberUpdatedState<(Float) -> Boolean> { availableY ->
        when {
            availableY < -1f -> { goNext(); true }  // 向下滚 → 下一个
            availableY > 1f  -> { goPrev(); true }  // 向上滚 → 上一个
            else -> false
        }
    }
    val wheelNavConnection = remember {
        object : NestedScrollConnection {
            var lastActionTime = 0L
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                val now = System.currentTimeMillis()
                if (now - lastActionTime < 350L) return Offset.Zero
                if (kotlin.math.abs(available.y) < 1f) return Offset.Zero
                return if (onWheel(available.y)) {
                    lastActionTime = now
                    available
                } else Offset.Zero
            }
        }
    }

    // ── 朗读模式对话框 ──
    if (showReadModeDialog) {
        val options = listOf(
            READ_WORD_ONLY to "只读单词",
            READ_SENTENCE_ONLY to "只读例句",
            READ_BOTH_WORD_FIRST to "都读 · 先读单词",
            READ_BOTH_SENTENCE_FIRST to "都读 · 先读例句",
        )
        AlertDialog(
            onDismissRequest = { showReadModeDialog = false },
            title = { Text("朗读方式") },
            text = {
                Column {
                    options.forEach { (value, label) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { readMode = value; showReadModeDialog = false }
                                .padding(vertical = 8.dp)
                        ) {
                            RadioButton(
                                selected = readMode == value,
                                onClick = { readMode = value; showReadModeDialog = false }
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(label)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showReadModeDialog = false }) { Text("确定") }
            }
        )
    }

    // ── UI ──
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (totalCount == 0) "语境猜词"
                        else "语境猜词 ${currentPos + 1}/$totalCount",
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { (context as? ContextGuessActivity)?.finish() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    // 朗读模式入口
                    val readLabel = when (readMode) {
                        READ_WORD_ONLY -> "读词"
                        READ_SENTENCE_ONLY -> "读句"
                        READ_BOTH_SENTENCE_FIRST -> "句+词"
                        else -> "词+句"
                    }
                    Text(
                        text = "朗读·$readLabel",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable { showReadModeDialog = true }
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                    )
                    // 计时
                    val mins = elapsedSeconds / 60
                    val secs = elapsedSeconds % 60
                    Text(
                        text = "${mins}:${"%02d".format(secs)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(end = 12.dp)
                    )
                }
            )
        }
    ) { padding ->
        if (allWords.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("没有单词，请返回重新选择")
            }
            return@Scaffold
        }

        val word = currentWord
        if (word == null) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("加载中…")
            }
            return@Scaffold
        }

        val effectiveMastered = repository.effectiveMastered(word.topicId, word.masteredInDb, overrides)

        BoxWithConstraints(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            val density = LocalDensity.current
            val isTablet = minOf(maxWidth, maxHeight) >= 600.dp
            val maxWpx = with(density) { maxWidth.toPx() }
            val maxHpx = with(density) { maxHeight.toPx() }
            val btnPx  = with(density) { 56.dp.toPx() }

            Column(modifier = Modifier.fillMaxSize()) {
            // 进度条
            if (totalCount > 0) {
                LinearProgressIndicator(
                    progress = { (currentPos + 1).toFloat() / totalCount },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp),
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            // ── 卡片内容（左右滑动翻页；点击切换翻译显隐）──
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .draggable(
                        orientation = Orientation.Horizontal,
                        state = rememberDraggableState { delta -> swipeDrag.floatValue += delta },
                        onDragStarted = { swipeDrag.floatValue = 0f },
                        onDragStopped = {
                            when {
                                swipeDrag.floatValue > 120f -> goPrev()  // 右滑 → 上一个
                                swipeDrag.floatValue < -120f -> goNext() // 左滑 → 下一个
                            }
                            swipeDrag.floatValue = 0f
                        }
                    ),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            ) {
              Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable { revealed = !revealed }  // 点击卡片：显示/隐藏翻译
                    .then(if (isTablet) Modifier.nestedScroll(wheelNavConnection) else Modifier)
              ) {
                // 已斩印章（右上角淡色水印）
                if (effectiveMastered) {
                    Text(
                        text = "斩",
                        fontSize = 96.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.10f),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(16.dp)
                    )
                }
                if (isTablet && !revealed) {
                    // 平板·未展开：单词/音标/例句 从上到下堆叠（大字）
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(tabletScrollState)
                            .padding(horizontal = 32.dp, vertical = 24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // 单词（大字，上限 240sp，短词放大、长词自动缩小适配宽度）
                        ContextGuessWordText(
                            word = word.word,
                            initialSize = 240f,
                            minSize = 40f,
                        )
                        // 音标
                        if (displayAccent.isNotBlank()) {
                            Spacer(Modifier.height(16.dp))
                            Text(
                                text = displayAccent,
                                fontSize = 30.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                        // 例句（大字，单词加粗高亮）
                        if (displaySentence.isNotBlank()) {
                            Spacer(Modifier.height(28.dp))
                            val annotatedStacked = buildHighlightedSentence(
                                sentence = displaySentence,
                                word = word.word,
                                highlightColor = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = annotatedStacked,
                                fontSize = 44.sp,
                                lineHeight = 59.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                            if (hasSentenceAudio) {
                                Spacer(Modifier.height(8.dp))
                                TextButton(onClick = ::speakSentence) {
                                    Text("♪ 播放例句", fontSize = 18.sp)
                                }
                            }
                        }
                    }
                } else if (isTablet) {
                    // 平板·已展开：单词作 50% 透明大字水印居中，例句/释义清晰叠在上层
                    ContextGuessWordText(
                        word = word.word,
                        initialSize = 240f,
                        minSize = 40f,
                        textAlpha = 0.5f,
                        modifier = Modifier.align(Alignment.Center)
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(tabletScrollState)
                            .padding(horizontal = 32.dp, vertical = 24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // 音标
                        if (displayAccent.isNotBlank()) {
                            Text(
                                text = displayAccent,
                                fontSize = 30.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(20.dp))
                        }
                        // 例句（大字，单词加粗高亮）
                        if (displaySentence.isNotBlank()) {
                            val annotatedTablet = buildHighlightedSentence(
                                sentence = displaySentence,
                                word = word.word,
                                highlightColor = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = annotatedTablet,
                                fontSize = 44.sp,
                                lineHeight = 59.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                            if (hasSentenceAudio) {
                                Spacer(Modifier.height(8.dp))
                                TextButton(onClick = ::speakSentence) {
                                    Text("♪ 播放例句", fontSize = 18.sp)
                                }
                            }
                        }
                        // 释义 + 例句翻译
                        Spacer(Modifier.height(16.dp))
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                            thickness = 2.dp
                        )
                        Spacer(Modifier.height(16.dp))
                        if (displayMeanCn.isNotBlank()) {
                            Text(
                                text = displayMeanCn,
                                fontSize = 48.sp,
                                lineHeight = 60.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        if (displayMeanEn.isNotBlank()) {
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = displayMeanEn,
                                fontSize = 26.sp,
                                lineHeight = 34.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        if (displaySentenceTrans.isNotBlank()) {
                            Spacer(Modifier.height(16.dp))
                            Text(
                                text = displaySentenceTrans,
                                fontSize = 30.sp,
                                lineHeight = 40.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // 单词（大字）
                    ContextGuessWordText(word = word.word)

                    // 音标
                    if (displayAccent.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = displayAccent,
                            fontSize = 20.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }

                    Spacer(Modifier.height(20.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                    Spacer(Modifier.height(16.dp))

                    // 例句（单词加粗高亮）
                    if (displaySentence.isNotBlank()) {
                        val annotated = buildHighlightedSentence(
                            sentence = displaySentence,
                            word = word.word,
                            highlightColor = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = annotated,
                            fontSize = 20.sp,
                            lineHeight = 29.sp,
                            textAlign = TextAlign.Start,
                            modifier = Modifier.fillMaxWidth()
                        )

                        // 例句播放按钮
                        if (hasSentenceAudio) {
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = ::speakSentence) {
                                Text("♪ 播放例句", fontSize = 14.sp)
                            }
                        }
                    }

                    // ── 展开区域：释义 + 例句翻译 ──
                    AnimatedVisibility(
                        visible = revealed,
                        enter = expandVertically() + fadeIn(),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Spacer(Modifier.height(16.dp))
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                                thickness = 2.dp
                            )
                            Spacer(Modifier.height(16.dp))

                            // 中文释义
                            if (displayMeanCn.isNotBlank()) {
                                Text(
                                    text = displayMeanCn,
                                    fontSize = 24.sp,
                                    lineHeight = 32.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.primary,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            // 英文释义
                            if (displayMeanEn.isNotBlank()) {
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    text = displayMeanEn,
                                    fontSize = 16.sp,
                                    lineHeight = 22.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            // 例句翻译
                            if (displaySentenceTrans.isNotBlank()) {
                                Spacer(Modifier.height(14.dp))
                                Text(
                                    text = displaySentenceTrans,
                                    fontSize = 16.sp,
                                    lineHeight = 22.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Start,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
                } // else（手机竖屏布局）
              } // Box
            }

            // ── 底部按钮：上一个 / 下一个 ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = ::goPrev,
                    enabled = currentIndex > 0,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("上一个", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                }
                Button(
                    onClick = ::goNext,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("下一个", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                }
            }

            Spacer(Modifier.height(8.dp))
            } // 内层 Column

            // ── 悬浮可拖动「斩」按钮（左下角初始位置）──
            var zhanX by remember { mutableFloatStateOf(with(density) { 16.dp.toPx() }) }
            var zhanY by remember { mutableFloatStateOf(maxHpx - btnPx - with(density) { 96.dp.toPx() }) }
            LaunchedEffect(maxWpx, maxHpx) {
                zhanX = zhanX.coerceIn(0f, (maxWpx - btnPx).coerceAtLeast(0f))
                zhanY = zhanY.coerceIn(0f, (maxHpx - btnPx).coerceAtLeast(0f))
            }
            Box(
                modifier = Modifier
                    .offset { IntOffset(zhanX.roundToInt(), zhanY.roundToInt()) }
                    .size(56.dp)
                    .background(
                        color = if (effectiveMastered) MaterialTheme.colorScheme.secondaryContainer
                                else MaterialTheme.colorScheme.errorContainer,
                        shape = CircleShape
                    )
                    .pointerInput(Unit) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            zhanX = (zhanX + drag.x).coerceIn(0f, maxWpx - btnPx)
                            zhanY = (zhanY + drag.y).coerceIn(0f, maxHpx - btnPx)
                        }
                    }
                    .clickable {
                        // 切换已斩/未斩（撤回斩），与闪卡 toggleMastered 一致
                        repository.toggleMastered(word.topicId, effectiveMastered)
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (effectiveMastered) "已斩" else "斩",
                    fontSize = if (effectiveMastered) 13.sp else 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (effectiveMastered) MaterialTheme.colorScheme.onSecondaryContainer
                            else MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

// ── 单词自适应大字 ──────────────────────────────────────────────

@Composable
private fun ContextGuessWordText(
    word: String,
    modifier: Modifier = Modifier,
    initialSize: Float = 72f,
    minSize: Float = 16f,
    textAlpha: Float = 1f,
) {
    var fontSize by remember(word, initialSize) { mutableStateOf(initialSize) }
    var measured by remember(word, initialSize) { mutableStateOf(false) }
    val color = MaterialTheme.colorScheme.onBackground
    Text(
        text = word,
        fontSize = TextUnit(fontSize, TextUnitType.Sp),
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = TextUnit(1f, TextUnitType.Sp),
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Clip,
        color = color.copy(alpha = textAlpha),
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (measured) 1f else 0f),
        onTextLayout = { result ->
            if (result.hasVisualOverflow && fontSize > minSize) fontSize *= 0.88f
            else if (!measured) measured = true
        }
    )
}

// ── 例句中高亮单词 ──────────────────────────────────────────────

@Composable
private fun buildHighlightedSentence(
    sentence: String,
    word: String,
    highlightColor: Color,
): androidx.compose.ui.text.AnnotatedString {
    return buildAnnotatedString {
        // 查找单词在例句中的位置（不区分大小写，支持词形变化的简单前缀匹配）
        val lowerSentence = sentence.lowercase()
        val lowerWord = word.lowercase()

        // 尝试精确匹配和简单变形（+s, +ed, +ing, +er, +est, +ly, +tion, +ness）
        val variants = mutableListOf(lowerWord)
        if (lowerWord.length >= 2) {
            variants.add(lowerWord + "s")
            variants.add(lowerWord + "es")
            variants.add(lowerWord + "ed")
            variants.add(lowerWord + "d")
            variants.add(lowerWord + "ing")
            variants.add(lowerWord + "er")
            variants.add(lowerWord + "est")
            variants.add(lowerWord + "ly")
            variants.add(lowerWord + "tion")
            variants.add(lowerWord + "ness")
            // 去 e 加 ing/ed
            if (lowerWord.endsWith("e")) {
                val stem = lowerWord.dropLast(1)
                variants.add(stem + "ing")
                variants.add(stem + "ed")
            }
            // 双写末字母加 ing/ed
            if (lowerWord.length >= 3) {
                val last = lowerWord.last()
                if (last in "bcdfgklmnprstvz") {
                    variants.add(lowerWord + last + "ing")
                    variants.add(lowerWord + last + "ed")
                }
            }
            // 去 y 加 ied/ies
            if (lowerWord.endsWith("y") && lowerWord.length >= 3) {
                val stem = lowerWord.dropLast(1)
                variants.add(stem + "ied")
                variants.add(stem + "ies")
            }
        }

        // 找到最长匹配
        data class Match(val start: Int, val length: Int)
        val matches = mutableListOf<Match>()
        for (variant in variants.sortedByDescending { it.length }) {
            var searchFrom = 0
            while (true) {
                val idx = lowerSentence.indexOf(variant, searchFrom)
                if (idx < 0) break
                // 检查是否是完整单词边界
                val before = if (idx > 0) lowerSentence[idx - 1] else ' '
                val after = if (idx + variant.length < lowerSentence.length)
                    lowerSentence[idx + variant.length] else ' '
                if (!before.isLetter() && !after.isLetter()) {
                    // 检查是否与已找到的匹配重叠
                    val overlaps = matches.any { m ->
                        idx < m.start + m.length && idx + variant.length > m.start
                    }
                    if (!overlaps) {
                        matches.add(Match(idx, variant.length))
                    }
                }
                searchFrom = idx + 1
            }
        }

        if (matches.isEmpty()) {
            // 没有匹配，直接显示原文
            append(sentence)
        } else {
            // 按位置排序，分段构建
            val sorted = matches.sortedBy { it.start }
            var pos = 0
            for (m in sorted) {
                if (m.start > pos) {
                    append(sentence.substring(pos, m.start))
                }
                withStyle(SpanStyle(
                    fontWeight = FontWeight.ExtraBold,
                    color = highlightColor,
                )) {
                    append(sentence.substring(m.start, m.start + m.length))
                }
                pos = m.start + m.length
            }
            if (pos < sentence.length) {
                append(sentence.substring(pos))
            }
        }
    }
}
