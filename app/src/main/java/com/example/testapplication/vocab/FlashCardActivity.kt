package com.example.testapplication.vocab

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.tencent.mmkv.MMKV
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

class FlashCardActivity : ComponentActivity() {
    companion object {
        const val EXTRA_FILTER            = "filter_type"
        const val EXTRA_START_INDEX       = "start_index"
        const val EXTRA_BOOK_ID           = "book_id"
        const val EXTRA_CROSS_BOOK_FILTER = "cross_book_filter"
    }

    internal var mediaSessionToggle: (() -> Unit)? = null
    internal var mediaSessionNext: (() -> Unit)? = null
    internal var mediaSessionPrev: (() -> Unit)? = null
    internal lateinit var mediaSession: android.support.v4.media.session.MediaSessionCompat

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()

        mediaSession = android.support.v4.media.session.MediaSessionCompat(this, "FlashCardSession").apply {
            setCallback(object : android.support.v4.media.session.MediaSessionCompat.Callback() {
                override fun onPlay()  { mediaSessionToggle?.invoke() }
                override fun onPause() { mediaSessionToggle?.invoke() }
                override fun onStop()  { mediaSessionToggle?.invoke() }
                override fun onSkipToNext()     { mediaSessionNext?.invoke() }
                override fun onSkipToPrevious() { mediaSessionPrev?.invoke() }
                override fun onMediaButtonEvent(mediaButtonEvent: Intent?): Boolean {
                    val event = mediaButtonEvent?.getParcelableExtra<android.view.KeyEvent>(Intent.EXTRA_KEY_EVENT)
                    if (event?.action == android.view.KeyEvent.ACTION_DOWN) {
                        when (event.keyCode) {
                            android.view.KeyEvent.KEYCODE_MEDIA_PLAY,
                            android.view.KeyEvent.KEYCODE_MEDIA_PAUSE,
                            android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                            android.view.KeyEvent.KEYCODE_HEADSETHOOK -> {
                                mediaSessionToggle?.invoke()
                                return true
                            }
                            android.view.KeyEvent.KEYCODE_MEDIA_NEXT -> {
                                mediaSessionNext?.invoke()
                                return true
                            }
                            android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                                mediaSessionPrev?.invoke()
                                return true
                            }
                        }
                    }
                    return super.onMediaButtonEvent(mediaButtonEvent)
                }
            })
            isActive = false
        }

        val filterType      = intent.getStringExtra(EXTRA_FILTER) ?: "unmastered"
        val startIndex      = intent.getIntExtra(EXTRA_START_INDEX, 0)
        val bookId          = intent.getIntExtra(EXTRA_BOOK_ID, WordBook.GAOKAO.id)
        val crossBookFilter = intent.getBooleanExtra(EXTRA_CROSS_BOOK_FILTER, false)
        setContent {
            com.example.testapplication.ui.theme.TestApplicationTheme {
                FlashCardScreen(filterType, startIndex, bookId, crossBookFilter)
            }
        }
    }

    override fun onDestroy() {
        if (::mediaSession.isInitialized) {
            mediaSession.isActive = false
            mediaSession.release()
        }
        super.onDestroy()
        WordPlaybackService.stop(this)
    }
}

// ── 主界面 ───────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlashCardScreen(filterType: String, startIndex: Int, bookId: Int, crossBookFilter: Boolean = false) {
    val context = LocalContext.current
    val repository = remember { WordRepository.getInstance(context) }
    val overrides       by repository.overrides.collectAsState()
    val crossBookCounts by repository.crossBookCounts.collectAsState()
    val bookTopicIds    by repository.bookTopicIds.collectAsState()
    val prefs = remember { MMKV.mmkvWithID("flashcard_prefs") }
    val scope = rememberCoroutineScope()

    // 确保正确词书已加载（冷启动 / 词书与当前不符时触发）
    LaunchedEffect(bookId) {
        val book = WordBook.entries.firstOrNull { it.id == bookId } ?: WordBook.GAOKAO
        if (repository.currentBook?.id != bookId) {
            scope.launch { repository.loadWords(book) }
        }
    }

    // 跨词书过滤：与 WordListActivity 相同逻辑（按 WordBook 枚举顺序优先级递减）
    val currentBook = WordBook.entries.firstOrNull { it.id == bookId } ?: WordBook.ZHONGKAO
    val excludedTopicIds = remember(currentBook, bookTopicIds, crossBookFilter) {
        if (!crossBookFilter) emptySet()
        else WordBook.entries
            .takeWhile { it != currentBook }
            .flatMap { bookTopicIds[it] ?: emptySet() }
            .toSet()
    }

    val loadStateVal by repository.loadState.collectAsState()
    val words = remember(loadStateVal, overrides, excludedTopicIds) {
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

    val currentIndexState = remember { mutableIntStateOf(startIndex.coerceIn(0, (words.size - 1).coerceAtLeast(0))) }
    var currentIndex by currentIndexState

    // 点击暂停：显示翻译、多读两遍后停下，再点击恢复
    val isPausedState = remember { mutableStateOf(false) }
    var isPaused by isPausedState
    val showTranslationState = remember { mutableStateOf(false) }
    var showTranslation by showTranslationState
    val pauseRepeatsState = remember { mutableIntStateOf(0) }   // 暂停期间额外朗读次数
    val pauseFinishedState = remember { mutableStateOf(false) } // 暂停的额外朗读已完成

    // ── 持久化设置（MMKV）──
    val autoReadState = remember { mutableStateOf(prefs.decodeBool("auto_read", false)) }
    var autoRead by autoReadState
    LaunchedEffect(autoRead) {
        prefs.encode("auto_read", autoRead)
        if (autoRead) {
            WordPlaybackService.start(context)
        } else {
            WordPlaybackService.stop(context)
        }
    }
    // 监听服务停止广播（用户从通知栏点"停止"时同步关闭 autoRead）
    // playerRef 用于在 BroadcastReceiver 中安全引用 player（player 在后面声明）
    val playerRef = remember { mutableStateOf<MediaPlayer?>(null) }
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == WordPlaybackService.ACTION_SERVICE_STOPPED) {
                    autoReadState.value = false
                    try { playerRef.value?.reset() } catch (_: Exception) {}
                }
            }
        }
        val filter = IntentFilter(WordPlaybackService.ACTION_SERVICE_STOPPED)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        onDispose {
            context.unregisterReceiver(receiver)
            WordPlaybackService.stop(context)
        }
    }

    var speechRate by remember { mutableFloatStateOf(prefs.decodeFloat("speech_rate", 1.0f)) }
    LaunchedEffect(speechRate) { prefs.encode("speech_rate", speechRate) }

    val wordRepeatCountState = remember { mutableIntStateOf(prefs.decodeInt("word_repeat_count", 1)) }
    var wordRepeatCount by wordRepeatCountState
    LaunchedEffect(wordRepeatCount) { prefs.encode("word_repeat_count", wordRepeatCount) }

    val readSentenceState = remember { mutableStateOf(prefs.decodeBool("read_sentence", false)) }
    var readSentenceEnabled by readSentenceState
    LaunchedEffect(readSentenceEnabled) { prefs.encode("read_sentence", readSentenceEnabled) }

    val sentenceRepeatCountState = remember { mutableIntStateOf(prefs.decodeInt("sentence_repeat_count", 1)) }
    var sentenceRepeatCount by sentenceRepeatCountState
    LaunchedEffect(sentenceRepeatCount) { prefs.encode("sentence_repeat_count", sentenceRepeatCount) }
    // 当前例句已朗读次数
    val currentSentenceRepeatState = remember { mutableIntStateOf(0) }
    // 是否正在朗读例句（用于显示例句覆盖层）
    val isSpeakingSentenceState = remember { mutableStateOf(false) }
    // 例句朗读时是否显示大字覆盖层
    val sentenceOverlayEnabledState = remember { mutableStateOf(prefs.decodeBool("sentence_overlay", true)) }
    var sentenceOverlayEnabled by sentenceOverlayEnabledState
    LaunchedEffect(sentenceOverlayEnabled) { prefs.encode("sentence_overlay", sentenceOverlayEnabled) }
    var sentenceOverlayAlpha by remember { mutableFloatStateOf(prefs.decodeFloat("sentence_overlay_alpha", 0.465f)) }
    LaunchedEffect(sentenceOverlayAlpha) { prefs.encode("sentence_overlay_alpha", sentenceOverlayAlpha) }

    // 当前词已朗读次数（切换单词时重置）
    val currentRepeatState = remember { mutableIntStateOf(0) }
    var currentRepeat by currentRepeatState

    // 学习轮次（所有单词都读过一遍 = 一轮），按词书+类型持久化
    val roundKey = "round_${bookId}_${filterType}"
    var roundCount by remember { mutableIntStateOf(prefs.decodeInt(roundKey, 0)) }
    // 从持久化的已读标记恢复（按当前 filterType 取对应桶）
    val readTopicIdsFlow = remember(filterType) { repository.readTopicIds(filterType) }
    val readTopicIds by readTopicIdsFlow.collectAsState()

    val showTransWhileReadState = remember { mutableStateOf(prefs.decodeBool("show_trans_while_read", false)) }
    var showTransWhileRead by showTransWhileReadState
    LaunchedEffect(showTransWhileRead) { prefs.encode("show_trans_while_read", showTransWhileRead) }

    var showRepeatDialog by remember { mutableStateOf(false) }
    var showSpeedDialog by remember { mutableStateOf(false) }
    var showTransModeDialog by remember { mutableStateOf(false) }
    var showTtsHelpDialog by remember { mutableStateOf(false) }
    var showRoundCompleteDialog by remember { mutableStateOf(false) }

    // 累积计时（秒）
    var elapsedSeconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000L)
            elapsedSeconds++
        }
    }

    var zpkFiles by remember { mutableStateOf<Map<String, ByteArray>?>(null) }
    var zpkMeta  by remember { mutableStateOf<ZpkMeta?>(null) }

    // 多例句轮播状态
    val zpkSentencesState               = remember { mutableStateOf<List<ZpkSentence>>(emptyList()) }
    // 当前展示的例句索引（仅在"正向进入"时推进，手动上一个/下一个不推进）
    val currentSentenceDisplayIndexState = remember { mutableIntStateOf(0) }
    // 控制下次加载单词时是否推进例句索引：初始为 true（从列表进入闪卡），
    // 之后仅 advanceToNext() 会重置为 true
    val advanceSentenceOnNextLoadState   = remember { mutableStateOf(true) }

    // ── 单 MediaPlayer（reset 复用，保持 audio session 热态）──
    val mediaAudioAttributes = remember {
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }
    val player = remember { MediaPlayer() }
    playerRef.value = player
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val audioFocusRequest = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(mediaAudioAttributes)
                .setWillPauseWhenDucked(false)
                .build()
        } else null
    }
    val cacheFile = remember { File(context.cacheDir, "zpk_word.mp3") }
    val nextAudioCache = remember { mutableStateOf<Pair<Int, ByteArray>?>(null) }

    // TTS 兜底
    val ttsRef = remember { mutableStateOf<TextToSpeech?>(null) }
    var ttsReady by remember { mutableStateOf(false) }
    var ttsErrorMsg by remember { mutableStateOf<String?>(null) }

    DisposableEffect(context) {
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                when (engine?.setLanguage(Locale.US)) {
                    TextToSpeech.LANG_MISSING_DATA  -> ttsErrorMsg = "缺少英语语音数据"
                    TextToSpeech.LANG_NOT_SUPPORTED -> ttsErrorMsg = "TTS 不支持英语"
                    else -> { ttsRef.value = engine; ttsReady = true }
                }
            } else { ttsErrorMsg = "TTS 引擎初始化失败" }
        }
        onDispose { engine?.shutdown(); ttsRef.value = null }
    }

    DisposableEffect(Unit) {
        onDispose {
            player.release()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            }
        }
    }

    // ── 导航 ──

    fun advanceToNext(fromIdx: Int) {
        advanceSentenceOnNextLoadState.value = true
        currentIndexState.intValue = if (fromIdx + 1 >= words.size) 0 else fromIdx + 1
    }

    fun goNext() {
        currentIndex = if (currentIndex < words.size - 1) currentIndex + 1 else 0
    }
    fun goPrev() { if (currentIndex > 0) currentIndex-- }

    // ── 音频工具 ──

    suspend fun getWordAudioBytes(wordIdx: Int): ByteArray? {
        nextAudioCache.value?.let { (idx, bytes) -> if (idx == wordIdx) return bytes }
        val word = words.getOrNull(wordIdx) ?: return null
        val files = if (wordIdx == currentIndex && zpkFiles != null) zpkFiles!!
                    else withContext(Dispatchers.IO) { repository.readZpk(word.topicId) } ?: return null
        return wordAudioBytesFromZpk(files)
    }

    fun precacheNext(playingIdx: Int) {
        val nextIdx = playingIdx + 1
        if (nextIdx >= words.size) return
        scope.launch {
            val word = words.getOrNull(nextIdx) ?: return@launch
            val bytes = withContext(Dispatchers.IO) {
                val files = repository.readZpk(word.topicId) ?: return@withContext null
                wordAudioBytesFromZpk(files)
            } ?: return@launch
            nextAudioCache.value = nextIdx to bytes
        }
    }

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
                // 请求音频焦点，防止后台被系统暂停
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    audioFocusRequest?.let { audioManager.requestAudioFocus(it) }
                }
                player.setOnCompletionListener { onComplete?.invoke() }
                if (speechRate != 1.0f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    try { player.playbackParams = PlaybackParams().setSpeed(speechRate) } catch (_: Exception) {}
                }
                player.start()
            }
        }
    }

    fun speakWordAt(wordIdx: Int) {
        scope.launch {
            val bytes = getWordAudioBytes(wordIdx) ?: run {
                val tts = ttsRef.value
                if (tts != null && ttsReady) {
                    words.getOrNull(wordIdx)?.let {
                        tts.setSpeechRate(speechRate)
                        tts.speak(it.word, TextToSpeech.QUEUE_FLUSH, null, "word")
                    }
                } else { showTtsHelpDialog = true }
                return@launch
            }

            playBytes(bytes) {
                if (!autoReadState.value) return@playBytes
                if (wordIdx != currentIndexState.intValue) return@playBytes

                // ── 暂停模式：额外朗读一遍后停下 ──
                if (isPausedState.value) {
                    if (pauseRepeatsState.intValue < 1) {
                        pauseRepeatsState.intValue++
                        speakWordAt(wordIdx)
                    } else {
                        pauseFinishedState.value = true
                    }
                    return@playBytes
                }

                // ── 从暂停恢复：当前朗读结束，跳到下一个 ──
                if (pauseRepeatsState.intValue > 0) {
                    pauseRepeatsState.intValue = 0
                    pauseFinishedState.value = false
                    advanceToNext(wordIdx)
                    return@playBytes
                }

                // ── 正常流程 ──
                currentRepeatState.intValue++
                if (currentRepeatState.intValue < wordRepeatCountState.intValue) {
                    speakWordAt(wordIdx)
                    return@playBytes
                }

                // 是否朗读例句
                if (readSentenceState.value) {
                    val sKey = zpkSentencesState.value
                        .getOrNull(currentSentenceDisplayIndexState.intValue)?.audio
                        ?: zpkMeta?.sentenceAudio
                    val sBytes = if (!sKey.isNullOrBlank()) zpkFiles?.get(sKey) else null
                    if (sBytes != null) {
                        currentSentenceRepeatState.intValue = 0
                        isSpeakingSentenceState.value = true
                        fun playSentenceLoop() {
                            playBytes(sBytes) {
                                if (isPausedState.value) {
                                    isSpeakingSentenceState.value = false
                                    pauseFinishedState.value = true
                                    return@playBytes
                                }
                                currentSentenceRepeatState.intValue++
                                if (currentSentenceRepeatState.intValue < sentenceRepeatCountState.intValue) {
                                    playSentenceLoop()
                                } else {
                                    isSpeakingSentenceState.value = false
                                    advanceToNext(wordIdx)
                                }
                            }
                        }
                        playSentenceLoop()
                        return@playBytes
                    }
                }

                advanceToNext(wordIdx)
            }

            precacheNext(wordIdx)
        }
    }

    fun speakSentence() {
        val audioKey = zpkSentencesState.value
            .getOrNull(currentSentenceDisplayIndexState.intValue)?.audio
            ?: zpkMeta?.sentenceAudio?.takeIf { it.isNotBlank() }
            ?: return
        val bytes = zpkFiles?.get(audioKey) ?: return
        playBytes(bytes)
    }

    // ── 蓝牙/耳机媒体按键：暂停切换 ──

    fun togglePlayPause() {
        if (!autoRead) return
        if (!isPaused) {
            isPaused = true
            showTranslation = true
            pauseRepeatsState.intValue = 0
            pauseFinishedState.value = false
            try { player.reset() } catch (_: Exception) {}
        } else {
            isPaused = false
            showTranslation = showTransWhileRead
            if (pauseFinishedState.value) {
                pauseRepeatsState.intValue = 0
                pauseFinishedState.value = false
                advanceToNext(currentIndexState.intValue)
            } else {
                pauseRepeatsState.intValue = 0
                speakWordAt(currentIndexState.intValue)
            }
        }
    }

    fun updateMediaSessionState(playing: Boolean) {
        val activity = context as? FlashCardActivity ?: return
        val state = android.support.v4.media.session.PlaybackStateCompat.Builder()
            .setActions(
                android.support.v4.media.session.PlaybackStateCompat.ACTION_PLAY or
                android.support.v4.media.session.PlaybackStateCompat.ACTION_PAUSE or
                android.support.v4.media.session.PlaybackStateCompat.ACTION_PLAY_PAUSE or
                android.support.v4.media.session.PlaybackStateCompat.ACTION_STOP or
                android.support.v4.media.session.PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                android.support.v4.media.session.PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
            )
            .setState(
                if (playing) android.support.v4.media.session.PlaybackStateCompat.STATE_PLAYING
                else android.support.v4.media.session.PlaybackStateCompat.STATE_PAUSED,
                android.support.v4.media.session.PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                if (playing) 1.0f else 0f
            )
            .build()
        activity.mediaSession.setPlaybackState(state)
    }

    // 绑定 MediaSession 回调到 Compose 状态
    DisposableEffect(Unit) {
        val activity = context as? FlashCardActivity
        activity?.mediaSessionToggle = { togglePlayPause() }
        activity?.mediaSessionNext = { goNext() }
        activity?.mediaSessionPrev = { goPrev() }
        onDispose {
            activity?.mediaSessionToggle = null
            activity?.mediaSessionNext = null
            activity?.mediaSessionPrev = null
        }
    }

    // autoRead / isPaused 变化时同步蓝牙播放状态 + MediaSession 激活状态
    LaunchedEffect(autoRead, isPaused) {
        val activity = context as? FlashCardActivity
        if (autoRead) {
            activity?.mediaSession?.isActive = true
            updateMediaSessionState(playing = !isPaused)
        } else {
            updateMediaSessionState(playing = false)
            activity?.mediaSession?.isActive = false
        }
    }

    // ── 加载 zpk + 自动朗读 ──

    val currentWord = words.getOrNull(currentIndex)

    // words.isEmpty() 作为 key：冷启动词书加载完成后重跑，避免第一个词漏掉 markRead
    LaunchedEffect(currentIndex, words.isEmpty()) {
        if (words.isEmpty()) return@LaunchedEffect
        if (!isPaused) showTranslation = showTransWhileRead
        currentRepeat = 0
        currentSentenceRepeatState.intValue = 0
        isSpeakingSentenceState.value = false
        pauseRepeatsState.intValue = 0
        pauseFinishedState.value = false
        // 标记当前词为已读（按 filterType 分桶，避免未斩/已斩互相清空）
        words.getOrNull(currentIndex)?.let { w ->
            repository.markRead(w.topicId, filterType)
            // 检查是否所有单词都已读过（一轮完成）
            val allTopicIds = words.map { it.topicId }.toSet()
            val updatedReadIds = readTopicIdsFlow.value
            if (allTopicIds.all { it in updatedReadIds }) {
                roundCount++
                prefs.encode(roundKey, roundCount)
                repository.clearReadMarks(filterType)
                isPaused = true
                showRoundCompleteDialog = true
            }
        }
        val word = words.getOrNull(currentIndex) ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val files = repository.readZpk(word.topicId)
            zpkFiles = files
            zpkMeta = files?.let { parseZpkMeta(it) }
            zpkSentencesState.value = files?.let { parseZpkSentences(it) } ?: emptyList()
        }

        // 多例句轮播：根据 advanceSentenceOnNextLoad 决定是否推进索引
        val sentences = zpkSentencesState.value
        if (sentences.isNotEmpty()) {
            val mmkvKey = "sentence_idx_${word.topicId}"
            val storedIdx = prefs.decodeInt(mmkvKey, 0).coerceIn(0, sentences.size - 1)
            currentSentenceDisplayIndexState.intValue = storedIdx
            if (advanceSentenceOnNextLoadState.value) {
                // 写入下次该词应播放的索引（循环取模）
                prefs.encode(mmkvKey, (storedIdx + 1) % sentences.size)
            }
        } else {
            currentSentenceDisplayIndexState.intValue = 0
        }
        // 无论是否推进，消耗本次标记（下次需要 advanceToNext 显式重置）
        advanceSentenceOnNextLoadState.value = false

        if (autoRead) speakWordAt(currentIndex)
    }

    val displayAccent        = currentWord?.accent?.ifBlank { zpkMeta?.accent ?: "" } ?: ""
    val displayMeanCn        = currentWord?.meanCn?.ifBlank { zpkMeta?.meanCn ?: "" } ?: ""
    val displayMeanEn        = zpkMeta?.meanEn ?: ""
    // 优先用当前轮播例句的文字，fallback 到 DB/meta 单条例句
    val currentZpkSentence   = zpkSentencesState.value.getOrNull(currentSentenceDisplayIndexState.intValue)
    val displaySentence      = currentZpkSentence?.sentenceEn?.ifBlank { null }
        ?: currentWord?.sentence?.ifBlank { zpkMeta?.sentence ?: "" } ?: ""
    val displaySentenceTrans = currentZpkSentence?.translate?.ifBlank { null }
        ?: currentWord?.sentenceTrans?.ifBlank { zpkMeta?.sentenceTrans ?: "" } ?: ""
    // parseZpkSentences 已验证 audio 存在于 zpk，currentZpkSentence != null 即代表有音频
    val hasSentenceAudio     = currentZpkSentence != null
        || (zpkMeta?.sentenceAudio?.isNotBlank() == true
            && zpkFiles?.containsKey(zpkMeta?.sentenceAudio) == true)

    // ── 对话框 ──

    if (showRoundCompleteDialog) {
        AlertDialog(
            onDismissRequest = { /* 不允许点外部关闭，必须主动选择 */ },
            title = { Text("第 $roundCount 轮完成 🎉") },
            text = { Text("已读完全部 ${words.size} 个单词！\n是否开始第 ${roundCount + 1} 轮？") },
            confirmButton = {
                TextButton(onClick = {
                    showRoundCompleteDialog = false
                    currentIndex = 0
                    isPaused = false
                }) { Text("开始第 ${roundCount + 1} 轮") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showRoundCompleteDialog = false
                    (context as? androidx.activity.ComponentActivity)?.finish()
                }) { Text("结束学习") }
            }
        )
    }

    if (showTtsHelpDialog) {
        AlertDialog(
            onDismissRequest = { showTtsHelpDialog = false },
            title = { Text("朗读不可用") },
            text = {
                Column {
                    Text(ttsErrorMsg ?: "该单词无 zpk 音频，TTS 也未就绪")
                    Spacer(Modifier.height(12.dp))
                    Text("解决方法：", fontWeight = FontWeight.Bold)
                    Text("1. 确保 assets/baicizhan/zpack/ 含 zpk 文件")
                    Text("2. 或安装 Google TTS 并下载英语语音包")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showTtsHelpDialog = false
                    try { context.startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
                    catch (_: Exception) { Toast.makeText(context, "请手动打开系统 TTS 设置", Toast.LENGTH_SHORT).show() }
                }) { Text("打开 TTS 设置") }
            },
            dismissButton = { TextButton(onClick = { showTtsHelpDialog = false }) { Text("知道了") } }
        )
    }

    if (showRepeatDialog) {
        AlertDialog(
            onDismissRequest = { showRepeatDialog = false },
            title = { Text("朗读设置") },
            text = {
                Column {
                    // 单词朗读遍数
                    Text("单词朗读遍数", fontWeight = FontWeight.Medium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        (1..6).forEach { n ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clickable { wordRepeatCount = n }
                                    .padding(vertical = 4.dp, horizontal = 4.dp)
                            ) {
                                RadioButton(selected = wordRepeatCount == n, onClick = { wordRepeatCount = n })
                                Text("${n}遍", fontSize = 14.sp)
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                    Spacer(Modifier.height(12.dp))

                    // 是否读例句
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { readSentenceEnabled = !readSentenceEnabled }
                    ) {
                        Checkbox(checked = readSentenceEnabled, onCheckedChange = { readSentenceEnabled = it })
                        Spacer(Modifier.width(4.dp))
                        Text("朗读例句")
                    }

                    // 例句朗读遍数（仅在开启读例句时显示）
                    if (readSentenceEnabled) {
                        Spacer(Modifier.height(8.dp))
                        Text("例句朗读遍数", fontWeight = FontWeight.Medium)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            (1..3).forEach { n ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .clickable { sentenceRepeatCount = n }
                                        .padding(vertical = 4.dp, horizontal = 4.dp)
                                ) {
                                    RadioButton(selected = sentenceRepeatCount == n, onClick = { sentenceRepeatCount = n })
                                    Text("${n}遍", fontSize = 14.sp)
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("覆盖层背景透明度", fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                            Text("${(sentenceOverlayAlpha * 100).toInt()}%",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Slider(
                            value = sentenceOverlayAlpha,
                            onValueChange = { sentenceOverlayAlpha = it },
                            valueRange = 0f..1f,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { sentenceOverlayEnabled = !sentenceOverlayEnabled }
                        ) {
                            Checkbox(checked = sentenceOverlayEnabled, onCheckedChange = { sentenceOverlayEnabled = it })
                            Spacer(Modifier.width(4.dp))
                            Text("例句朗读时显示大字覆盖层")
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showRepeatDialog = false }) { Text("确定") } }
        )
    }

    if (showSpeedDialog) {
        AlertDialog(
            onDismissRequest = { showSpeedDialog = false },
            title = { Text("朗读速度") },
            text = {
                Column {
                    speedOptions.forEachIndexed { i, rate ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { speechRate = rate; showSpeedDialog = false }
                                .padding(vertical = 8.dp)
                        ) {
                            RadioButton(
                                selected = speechRate == rate,
                                onClick = { speechRate = rate; showSpeedDialog = false }
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(speedLabels[i])
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showSpeedDialog = false }) { Text("确定") } }
        )
    }

    if (showTransModeDialog) {
        AlertDialog(
            onDismissRequest = { showTransModeDialog = false },
            title = { Text("朗读时翻译显示") },
            text = {
                Column {
                    listOf(false to "朗读时不显示翻译", true to "朗读时默认显示翻译").forEach { (value, label) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showTransWhileRead = value; showTransModeDialog = false; showTranslation = value }
                                .padding(vertical = 8.dp)
                        ) {
                            RadioButton(
                                selected = showTransWhileRead == value,
                                onClick = { showTransWhileRead = value; showTransModeDialog = false; showTranslation = value }
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(label)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showTransModeDialog = false }) { Text("确定") } }
        )
    }

    // ── UI ──

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (words.isEmpty()) "无单词"
                        else {
                            val round = "第${roundCount + 1}轮"
                            val allTopicIds = words.map { it.topicId }.toSet()
                            val read = readTopicIds.count { it in allTopicIds }
                            "$round ${currentIndex + 1}/${words.size} (已读$read)"
                        },
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { (context as? FlashCardActivity)?.finish() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 朗读设置按钮
                        val settingsLabel = buildString {
                            append("词${wordRepeatCount}")
                            if (readSentenceEnabled) append(" 句${sentenceRepeatCount}")
                        }
                        Text(
                            text = settingsLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clickable { showRepeatDialog = true }
                                .padding(horizontal = 6.dp, vertical = 4.dp)
                        )
                        // 自动朗读（高亮/置灰文本切换）
                        Text(
                            text = "自动朗读",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = if (autoRead) FontWeight.Bold else FontWeight.Normal,
                            color = if (autoRead) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outline,
                            modifier = Modifier
                                .clickable { autoRead = !autoRead }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            )
        }
    ) { padding ->
        if (words.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("没有单词，请返回重新选择")
            }
            return@Scaffold
        }

        val word = currentWord!!
        val effectiveMastered = repository.effectiveMastered(word.topicId, word.masteredInDb, overrides)
        val wordDisplayColor = if (filterType == "unmastered") {
            when (crossBookCounts[word.topicId] ?: 1) {
                5    -> Color(0xFFFF5252) // 5 本词书都出现 → 亮红
                4    -> Color(0xFFE040FB) // 出现 4 次 → 亮紫
                3    -> Color(0xFFFFD740) // 出现 3 次 → 琥珀黄
                2    -> Color(0xFF69F0AE) // 出现 2 次 → 薄荷绿
                else -> Color.Unspecified // 仅当前词书 → 默认白色
            }
        } else Color.Unspecified
        val density = LocalDensity.current

        BoxWithConstraints(modifier = Modifier.padding(padding).fillMaxSize()) {
            // 使用 scope 的 Dp 属性（满足 BoxWithConstraintsUnusedScope lint 要求）
            val maxWDp = maxWidth
            val maxHDp = maxHeight
            val isTablet = minOf(maxWDp, maxHDp) >= 600.dp
            val maxW = with(density) { maxWDp.toPx() }
            val maxH = with(density) { maxHDp.toPx() }
            val btnPx = with(density) { 56.dp.toPx() }

            var floatX by remember { mutableFloatStateOf(maxW - btnPx - with(density) { 16.dp.toPx() }) }
            var floatY by remember { mutableFloatStateOf(maxH * 0.4f) }
            // 屏幕尺寸变化时约束悬浮按钮位置
            LaunchedEffect(maxW, maxH) {
                floatX = floatX.coerceIn(0f, (maxW - btnPx).coerceAtLeast(0f))
                floatY = floatY.coerceIn(0f, (maxH - btnPx).coerceAtLeast(0f))
            }

            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 翻译显示模式按钮
                        Text(
                            text = if (showTransWhileRead) "显译" else "隐译",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (showTransWhileRead) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outline,
                            modifier = Modifier
                                .clickable { showTransModeDialog = true }
                                .padding(vertical = 4.dp, horizontal = 4.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        // 速度按钮
                        val rateLabel = speedLabels[speedOptions.indexOf(speechRate).coerceAtLeast(0)]
                        Text(
                            text = "速度 $rateLabel",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clickable { showSpeedDialog = true }
                                .padding(vertical = 4.dp)
                        )
                    }
                    // 累积时间
                    val mins = elapsedSeconds / 60
                    val secs = elapsedSeconds % 60
                    Text(
                        text = "${mins}分${secs}秒",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }

                // ── 卡片（点击暂停/恢复，暂停时右滑查看上一个）──
                val swipeDrag = remember { mutableFloatStateOf(0f) }
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .then(
                            if (isPaused) Modifier.draggable(
                                orientation = Orientation.Horizontal,
                                state = rememberDraggableState { delta -> swipeDrag.floatValue += delta },
                                onDragStarted = { swipeDrag.floatValue = 0f },
                                onDragStopped = {
                                    if (swipeDrag.floatValue > 80f && currentIndexState.intValue > 0) {
                                        // 右滑：查看上一个词（保持暂停）
                                        currentIndexState.intValue--
                                    }
                                    swipeDrag.floatValue = 0f
                                }
                            ) else Modifier
                        ),
                    shape = RectangleShape,
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                ) {
                    val cardClickModifier = Modifier.clickable {
                        if (!isPaused) {
                            isPaused = true
                            showTranslation = true
                            pauseRepeatsState.intValue = 0
                            pauseFinishedState.value = false
                        } else {
                            isPaused = false
                            showTranslation = showTransWhileRead
                            if (pauseFinishedState.value) {
                                // 额外朗读已结束，跳下一个
                                pauseRepeatsState.intValue = 0
                                pauseFinishedState.value = false
                                if (autoRead) advanceToNext(currentIndexState.intValue)
                            } else if (!player.isPlaying && autoRead) {
                                // 没有在播放中（如右滑切换过单词），重新开始朗读
                                pauseRepeatsState.intValue = 0
                                speakWordAt(currentIndexState.intValue)
                            }
                            // 否则朗读还在进行中，等 completion listener 读完后自动跳
                        }
                    }

                    Box(modifier = Modifier.fillMaxSize()) {
                        // 已斩水印
                        if (effectiveMastered) {
                            Text(
                                text = "斩",
                                fontSize = 180.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error.copy(alpha = 0.07f),
                                modifier = Modifier.align(Alignment.Center)
                            )
                        }
                        Column(modifier = Modifier.fillMaxSize()) {
                        // 上部：可滚动的主内容
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .then(cardClickModifier)
                                .padding(horizontal = 7.dp, vertical = 7.dp)
                                .verticalScroll(rememberScrollState()),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            // 单词
                            AdaptiveWordText(word = word.word, color = wordDisplayColor)

                            // [暂停] 中文释义（在单词下方）
                            if (showTranslation && displayMeanCn.isNotBlank()) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = displayMeanCn,
                                    fontSize = if (isTablet) 37.sp else 22.sp,
                                    lineHeight = if (isTablet) 48.sp else 30.sp,
                                    fontWeight = FontWeight.Medium,
                                    textAlign = TextAlign.Center,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            // 音标
                            if (displayAccent.isNotBlank()) {
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    text = displayAccent,
                                    fontSize = if (isTablet) 35.sp else 20.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            }

                            // 英文释义
                            if (displayMeanEn.isNotBlank()) {
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    text = displayMeanEn,
                                    fontSize = if (isTablet) 33.sp else 18.sp,
                                    lineHeight = if (isTablet) 44.sp else 24.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            // 例句（英文，始终显示）
                            val isSpeakingSentence = isSpeakingSentenceState.value
                            if (displaySentence.isNotBlank()) {
                                Spacer(Modifier.height(16.dp))
                                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                                Spacer(Modifier.height(12.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = displaySentence,
                                        fontSize = if (isTablet) 32.sp else 17.sp,
                                        lineHeight = if (isTablet) 42.sp else 23.sp,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (hasSentenceAudio) {
                                        TextButton(onClick = ::speakSentence) {
                                            Text("♪", fontSize = 20.sp, color = MaterialTheme.colorScheme.secondary)
                                        }
                                    }
                                }
                                // [暂停] 例句翻译
                                if (showTranslation && displaySentenceTrans.isNotBlank()) {
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        text = displaySentenceTrans,
                                        fontSize = if (isTablet) 30.sp else 15.sp,
                                        lineHeight = if (isTablet) 40.sp else 21.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Start,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }

                            if (!showTranslation) {
                                Spacer(Modifier.height(16.dp))
                                Text(
                                    "点击暂停查看释义",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }

                        // 下部：上一个读过的单词（固定在卡片底部）
                        val prevWord = if (currentIndex > 0) words.getOrNull(currentIndex - 1) else null
                        if (prevWord != null) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 7.dp, vertical = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = prevWord.word,
                                    fontSize = 21.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                                val prevMeanCn = prevWord.meanCn.ifBlank { "" }
                                if (prevMeanCn.isNotBlank()) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = prevMeanCn,
                                        fontSize = 15.sp,
                                        color = MaterialTheme.colorScheme.outline,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }
                }
                } // Card

                Spacer(Modifier.height(6.dp))

                // ── 底部：上一个 + 下一个 + 未斩（2:2:1）──
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            isPaused = true
                            showTranslation = true
                            pauseRepeatsState.intValue = 0
                            pauseFinishedState.value = true
                            try { player.reset() } catch (_: Exception) {}
                            goPrev()
                        },
                        enabled = currentIndex > 0,
                        modifier = Modifier.weight(2f)
                    ) {
                        Text("上一个")
                    }
                    OutlinedButton(onClick = ::goNext, modifier = Modifier.weight(2f)) {
                        Text("下一个")
                    }
                    OutlinedButton(
                        onClick = {
                            repository.toggleMastered(word.topicId, effectiveMastered)
                            // 斩了后停止当前播放并跳下一个
                            if (!effectiveMastered) {
                                try { player.reset() } catch (_: Exception) {}
                                goNext()
                            }
                        },
                        colors = if (effectiveMastered)
                            ButtonDefaults.outlinedButtonColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer
                            )
                        else ButtonDefaults.outlinedButtonColors(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            if (effectiveMastered) "已斩" else "斩",
                            fontSize = 14.sp
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))
            } // Column(fillMaxSize)

            // ── 例句朗读覆盖层（无 pointer input，点击透传到卡片）──
            val sentenceFontSize = if (isTablet) 95.sp else 50.sp
            val sentenceLineHeight = if (isTablet) 110.sp else 60.sp
            if (isSpeakingSentenceState.value && sentenceOverlayEnabled && displaySentence.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth()
                        .background(
                            color = MaterialTheme.colorScheme.surface.copy(alpha = sentenceOverlayAlpha),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = displaySentence,
                            fontSize = sentenceFontSize,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                            lineHeight = sentenceLineHeight
                        )
                        if (showTranslation && displaySentenceTrans.isNotBlank()) {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = displaySentenceTrans,
                                fontSize = if (isTablet) 33.sp else 18.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }

            // ── 悬浮可拖动朗读按钮 ──
            Box(
                modifier = Modifier
                    .offset { IntOffset(floatX.roundToInt(), floatY.roundToInt()) }
                    .size(56.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
                    .pointerInput(Unit) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            floatX = (floatX + drag.x).coerceIn(0f, maxW - btnPx)
                            floatY = (floatY + drag.y).coerceIn(0f, maxH - btnPx)
                        }
                    }
                    .clickable { speakWordAt(currentIndex) },
                contentAlignment = Alignment.Center
            ) {
                Text("♪", fontSize = 24.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}

// ── 自适应字号 ───────────────────────────────────────────────────

@Composable
private fun AdaptiveWordText(word: String, color: Color = Color.Unspecified) {
    var fontSize by remember(word) { mutableStateOf(200f) }
    var measured  by remember(word) { mutableStateOf(false) }
    val resolvedColor = if (color == Color.Unspecified) MaterialTheme.colorScheme.onBackground else color
    Text(
        text = word,
        fontSize = TextUnit(fontSize, TextUnitType.Sp),
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = TextUnit(1.5f, TextUnitType.Sp),
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Clip,
        color = resolvedColor,
        modifier = Modifier.fillMaxWidth().alpha(if (measured) 1f else 0f),
        onTextLayout = { result ->
            if (result.hasVisualOverflow && fontSize > 12f) fontSize *= 0.85f
            else if (!measured) measured = true
        }
    )
}

// ── 速度选项 ─────────────────────────────────────────────────────

private val speedOptions = listOf(1.0f, 1.1f, 1.2f, 1.3f, 1.4f)
private val speedLabels  = listOf("1x", "1.1x", "1.2x", "1.3x", "1.4x")
