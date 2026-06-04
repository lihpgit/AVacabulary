package com.example.testapplication.desktop

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.example.testapplication.vocab.Word
import com.example.testapplication.vocab.WordBook
import com.example.testapplication.vocab.ZpkMeta
import com.example.testapplication.vocab.ZpkSentence
import com.example.testapplication.vocab.parseZpkMeta
import com.example.testapplication.vocab.parseZpkSentences
import com.example.testapplication.vocab.wordAudioBytesFromZpk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// 朗读模式
internal const val READ_WORD_ONLY = 0
internal const val READ_SENTENCE_ONLY = 1
internal const val READ_BOTH_WORD_FIRST = 2
internal const val READ_BOTH_SENTENCE_FIRST = 3

// 4 种过滤视图：未斩 / 已斩 / 未斩不认识 / 已斩不认识
// “不认识”= 猜词时查看过翻译，与斩/未斩正交
internal const val FILTER_UNMASTERED = 0
internal const val FILTER_MASTERED = 1
internal const val FILTER_UNMASTERED_UNKNOWN = 2
internal const val FILTER_MASTERED_UNKNOWN = 3

/**
 * 猜词页的全部状态 + 逻辑，独立于任何窗口。
 *
 * 普通窗口和"贴底"窗口共享同一个实例，因此切换窗口时学习进度、当前单词、播放等完全连续。
 * 所有响应式副作用（加载词书 / 读 zpk / 自动朗读 / 计时 / 存进度）都在 [start] 里用
 * snapshotFlow 驱动，跟窗口的可见性无关 —— 贴底时朗读照样工作。
 */
class GuessState(
    val repo: DesktopRepository,
    val prefs: DesktopPrefs,
    val audio: DesktopAudio,
) {
    var book by mutableStateOf(
        WordBook.entries.firstOrNull { it.id == prefs.getInt("selected_book_id", WordBook.ZHONGKAO.id) }
            ?: WordBook.ZHONGKAO
    )
    var filterMode by mutableIntStateOf(prefs.getInt("filter_mode", FILTER_UNMASTERED))
    var crossBookFilter by mutableStateOf(prefs.getInt("cross_book_filter", 0) == 1)
    var overrides by mutableStateOf(prefs.loadOverrides())
    var readMode by mutableIntStateOf(prefs.getInt("read_mode", READ_BOTH_WORD_FIRST))

    // “不认识”集合（全局 topicId）+ 会话快照（切换视图/词书才刷新，浏览中列表稳定）
    var notRecognized by mutableStateOf(prefs.loadNotRecognized())
    private var nrSnapshot by mutableStateOf(prefs.loadNotRecognized())
    // 本词本次浏览是否查看过翻译（决定离开时是否从“不认识”移出）
    private var revealedThisVisit by mutableStateOf(false)

    /** 普通窗口：鼠标离开后是否自动变透明（默认开） */
    var autoTransparent by mutableStateOf(prefs.getInt("auto_transparent", 1) == 1)

    var allWords by mutableStateOf<List<Word>>(emptyList())
    var loading by mutableStateOf(true)
    var bookTopicIds by mutableStateOf<Map<WordBook, Set<Int>>>(emptyMap())

    var currentIndex by mutableIntStateOf(0)
    var revealed by mutableStateOf(false)

    var zpkFiles by mutableStateOf<Map<String, ByteArray>?>(null)
    var zpkMeta by mutableStateOf<ZpkMeta?>(null)
    var sentences by mutableStateOf<List<ZpkSentence>>(emptyList())
    var sentenceIdx by mutableIntStateOf(0)

    var elapsed by mutableIntStateOf(0)

    // ── 派生（derivedStateOf：仅在依赖的 state 变化时重算，并缓存结果）──
    // 关键：excluded 只算一次（之前被 filter lambda 每个单词都重算一遍，3000×，是卡顿主因）。
    private val excludedState = derivedStateOf {
        if (!crossBookFilter) emptySet()
        else WordBook.entries.takeWhile { it != book }
            .flatMap { bookTopicIds[it] ?: emptySet() }.toSet()
    }
    val excluded: Set<Int> get() = excludedState.value

    private fun matches(w: Word, mode: Int, nr: Set<Int>): Boolean {
        val mastered = overrides[w.topicId] ?: w.masteredInDb
        return when (mode) {
            FILTER_MASTERED           -> mastered
            FILTER_UNMASTERED_UNKNOWN -> !mastered && w.topicId in nr
            FILTER_MASTERED_UNKNOWN   -> mastered  && w.topicId in nr
            else                      -> !mastered // FILTER_UNMASTERED
        }
    }

    private val wordsState = derivedStateOf {
        val ex = excludedState.value
        val nr = nrSnapshot
        allWords.filter { matches(it, filterMode, nr) && it.topicId !in ex }
    }
    val words: List<Word> get() = wordsState.value

    val currentWord: Word? get() = words.getOrNull(currentIndex)

    val effectiveMastered: Boolean
        get() = currentWord?.let { overrides[it.topicId] ?: it.masteredInDb } ?: false

    private fun progressKey(b: WordBook = book, mode: Int = filterMode) =
        "progress_${b.id}_$mode"

    // ── “不认识”标记 ──
    fun markNotRecognized(topicId: Int) {
        if (topicId in notRecognized) return
        notRecognized = notRecognized + topicId
        prefs.saveNotRecognized(notRecognized)
    }

    fun unmarkNotRecognized(topicId: Int) {
        if (topicId !in notRecognized) return
        notRecognized = notRecognized - topicId
        prefs.saveNotRecognized(notRecognized)
    }

    fun updateFilterMode(v: Int) { filterMode = v; prefs.putInt("filter_mode", v) }

    // ── 朗读 ──
    fun playWord(onComplete: (() -> Unit)? = null) {
        val bytes = zpkFiles?.let { wordAudioBytesFromZpk(it) }
        if (bytes != null) audio.play(bytes, "${currentWord?.word} word", onComplete)
        else { audio.stop(); onComplete?.invoke() }
    }

    fun playSentence(onComplete: (() -> Unit)? = null) {
        val key = sentences.getOrNull(sentenceIdx)?.audio
            ?: zpkMeta?.sentenceAudio?.takeIf { it.isNotBlank() }
        val bytes = key?.let { zpkFiles?.get(it) }
        if (bytes != null) audio.play(bytes, "${currentWord?.word} sent", onComplete)
        else { audio.stop(); onComplete?.invoke() }
    }

    fun autoPlay() {
        val tid = currentWord?.topicId ?: return
        when (readMode) {
            READ_WORD_ONLY -> playWord()
            READ_SENTENCE_ONLY -> playSentence()
            READ_BOTH_SENTENCE_FIRST -> playSentence { if (currentWord?.topicId == tid) playWord() }
            else -> playWord { if (currentWord?.topicId == tid) playSentence() }
        }
    }

    // 离开当前词：未查看翻译则从“不认识”移出（列表为会话快照，不会即时塌缩）
    private fun leaveCurrentWord() {
        val leaving = currentWord
        val wasRevealed = revealedThisVisit || revealed
        revealedThisVisit = false
        revealed = false
        if (leaving != null && !wasRevealed) unmarkNotRecognized(leaving.topicId)
    }

    // ── 导航 ──
    fun goPrev() {
        if (currentIndex <= 0) return
        leaveCurrentWord(); currentIndex--
    }

    fun goNext() {
        if (words.isEmpty()) return
        leaveCurrentWord()
        currentIndex = if (currentIndex + 1 >= words.size) 0 else currentIndex + 1
    }

    fun toggleReveal() {
        revealed = !revealed
        // 查看翻译 → 标记“不认识”（同步置位，保证 leaveCurrentWord 能读到）
        if (revealed) {
            revealedThisVisit = true
            currentWord?.let { markNotRecognized(it.topicId) }
        }
    }

    fun toggleMastered() {
        val w = currentWord ?: return
        val eff = overrides[w.topicId] ?: w.masteredInDb
        overrides = overrides.toMutableMap().also { it[w.topicId] = !eff }
        prefs.saveOverrides(overrides)
    }

    fun updateReadMode(v: Int) { readMode = v; prefs.putInt("read_mode", v) }
    fun updateCrossBookFilter(v: Boolean) { crossBookFilter = v; prefs.putInt("cross_book_filter", if (v) 1 else 0) }
    fun updateAutoTransparent(v: Boolean) { autoTransparent = v; prefs.putInt("auto_transparent", if (v) 1 else 0) }

    // ── 响应式副作用（在后台 scope 启动一次，避免占用 UI 线程）──
    fun start(scope: CoroutineScope) {
        // 启动即在后台把 zpk 索引建好 + 加载跨词书集合
        scope.launch(Dispatchers.IO) { repo.prewarmIndex() }
        scope.launch { bookTopicIds = withContext(Dispatchers.IO) { repo.bookTopicIds() } }

        // 切词书 → 加载单词
        scope.launch {
            snapshotFlow { book }.collectLatest { b ->
                loading = true
                allWords = withContext(Dispatchers.IO) {
                    runCatching { repo.loadWords(b) }.getOrDefault(emptyList())
                }
                loading = false
            }
        }
        // 切词书 → 记住选择（下次启动恢复）
        scope.launch { snapshotFlow { book }.collect { prefs.putInt("selected_book_id", it.id) } }
        // 切 词书/过滤视图 → 刷新“不认识”会话快照（让列表稳定，浏览中不塌缩）+ 恢复浏览进度
        scope.launch {
            snapshotFlow { book to filterMode }.collect { (b, mode) ->
                nrSnapshot = notRecognized
                currentIndex = prefs.getInt(progressKey(b, mode), 0).coerceAtLeast(0)
            }
        }
        // 单词集合大小变化 → 夹紧下标
        scope.launch {
            snapshotFlow { words.size }.collect { sz ->
                if (sz > 0) currentIndex = currentIndex.coerceIn(0, sz - 1)
            }
        }
        // 下标变化 → 存进度
        scope.launch {
            snapshotFlow { currentIndex }.collect { idx ->
                if (words.isNotEmpty()) prefs.putInt(progressKey(), idx)
            }
        }
        // 当前词变化 → 读 zpk + 防抖后自动朗读
        scope.launch {
            snapshotFlow { currentWord?.topicId }.collectLatest { tid ->
                if (tid == null) return@collectLatest
                val w = currentWord ?: return@collectLatest
                val files = withContext(Dispatchers.IO) { runCatching { repo.readZpk(w.topicId) }.getOrNull() }
                zpkFiles = files
                zpkMeta = files?.let { parseZpkMeta(it) }
                val ss = files?.let { parseZpkSentences(it) } ?: emptyList()
                sentences = ss
                if (ss.isNotEmpty()) {
                    val key = "sentence_idx_${w.topicId}"
                    val stored = prefs.getInt(key, 0).coerceIn(0, ss.size - 1)
                    sentenceIdx = stored
                    prefs.putInt(key, (stored + 1) % ss.size)
                } else sentenceIdx = 0
                delay(110) // 防抖：快速翻页时取消上一次，避免吞音
                autoPlay()
                // 预取相邻单词的 zpk，让下一次翻页即时出声
                launch(Dispatchers.IO) {
                    val ws = words
                    ws.getOrNull(currentIndex + 1)?.topicId?.let { repo.prefetchZpk(it) }
                    ws.getOrNull(currentIndex - 1)?.topicId?.let { repo.prefetchZpk(it) }
                }
            }
        }
        // 计时
        scope.launch { while (true) { delay(1000); elapsed++ } }
    }
}
