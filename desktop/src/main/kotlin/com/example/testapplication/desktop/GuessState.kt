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
    var filterMastered by mutableStateOf(false)          // false=未斩
    var crossBookFilter by mutableStateOf(prefs.getInt("cross_book_filter", 0) == 1)
    var overrides by mutableStateOf(prefs.loadOverrides())
    var readMode by mutableIntStateOf(prefs.getInt("read_mode", READ_BOTH_WORD_FIRST))

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

    private val wordsState = derivedStateOf {
        val ex = excludedState.value
        allWords.filter {
            (overrides[it.topicId] ?: it.masteredInDb) == filterMastered && it.topicId !in ex
        }
    }
    val words: List<Word> get() = wordsState.value

    val currentWord: Word? get() = words.getOrNull(currentIndex)

    val effectiveMastered: Boolean
        get() = currentWord?.let { overrides[it.topicId] ?: it.masteredInDb } ?: false

    private fun progressKey(b: WordBook = book, fm: Boolean = filterMastered) =
        "progress_${b.id}_${if (fm) "m" else "u"}"

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

    // ── 导航 ──
    fun goPrev() {
        if (currentIndex <= 0) return
        revealed = false; currentIndex--
    }

    fun goNext() {
        if (words.isEmpty()) return
        revealed = false
        currentIndex = if (currentIndex + 1 >= words.size) 0 else currentIndex + 1
    }

    fun toggleReveal() { revealed = !revealed }

    fun toggleMastered() {
        val w = currentWord ?: return
        val eff = overrides[w.topicId] ?: w.masteredInDb
        overrides = overrides.toMutableMap().also { it[w.topicId] = !eff }
        prefs.saveOverrides(overrides)
    }

    fun updateReadMode(v: Int) { readMode = v; prefs.putInt("read_mode", v) }
    fun updateCrossBookFilter(v: Boolean) { crossBookFilter = v; prefs.putInt("cross_book_filter", if (v) 1 else 0) }

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
        // 切 词书/未斩已斩 → 恢复该组合的浏览进度
        scope.launch {
            snapshotFlow { book to filterMastered }.collect { (b, fm) ->
                currentIndex = prefs.getInt(progressKey(b, fm), 0).coerceAtLeast(0)
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
