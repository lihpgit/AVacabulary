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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// 朗读模式
internal const val READ_WORD_ONLY = 0
internal const val READ_SENTENCE_ONLY = 1
internal const val READ_BOTH_WORD_FIRST = 2
internal const val READ_BOTH_SENTENCE_FIRST = 3

// 3 种过滤视图（互斥）：未斩 / 已斩 / 不认识
// “不认识”= 猜词翻面看过翻译；未斩/已斩均排除不认识
internal const val FILTER_UNMASTERED = 0
internal const val FILTER_MASTERED = 1
internal const val FILTER_UNKNOWN = 2

/** 旧 filter_mode（2=未斩不认识 / 3=已斩不认识）统一归一化为 2=不认识 */
internal fun normalizeFilterMode(mode: Int): Int =
    if (mode >= FILTER_UNKNOWN) FILTER_UNKNOWN else mode

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
    var filterMode by mutableIntStateOf(normalizeFilterMode(prefs.getInt("filter_mode", FILTER_UNMASTERED)))
    var crossBookFilter by mutableStateOf(prefs.getInt("cross_book_filter", 0) == 1)
    var overrides by mutableStateOf(prefs.loadOverrides())
    var readMode by mutableIntStateOf(prefs.getInt("read_mode", READ_BOTH_WORD_FIRST))

    // “不认识”集合（全局 topicId）+ 会话快照（切换视图/词书才刷新，浏览中列表稳定）
    var notRecognized by mutableStateOf(prefs.loadNotRecognized())
    private var nrSnapshot by mutableStateOf(prefs.loadNotRecognized())

    /** 普通窗口：鼠标离开后是否自动变透明（默认开） */
    var autoTransparent by mutableStateOf(prefs.getInt("auto_transparent", 1) == 1)

    /** 自动连续朗读（仅普通窗口在最前时运行）：读完当前词自动翻下一词、循环；失焦/没蓝牙即停。 */
    var autoPlaying by mutableStateOf(false)
    /** 单词朗读遍数（自动朗读时生效），1–6，持久化 */
    var wordRepeat by mutableStateOf(prefs.getInt("word_repeat_count", 1).coerceIn(1, 6))
    /** 例句朗读遍数（自动朗读时生效），1–3，持久化 */
    var sentenceRepeat by mutableStateOf(prefs.getInt("sentence_repeat_count", 1).coerceIn(1, 3))
    /** 自动朗读时是否自动翻面显示翻译，持久化 */
    var showTransWhileRead by mutableStateOf(prefs.getInt("show_trans_while_read", 0) == 1)

    /** 仅蓝牙耳机才朗读（防外放）：默认开。关闭后任何输出设备都可朗读（在家用扬声器） */
    var bluetoothOnly by mutableStateOf(prefs.getInt("bluetooth_only", 1) == 1)
    /** 后台轮询缓存：当前默认音频输出设备是否为蓝牙（勿在 EDT 直接探测） */
    @Volatile private var btOutputConnected = false
    /** 是否允许朗读：未开「仅蓝牙」或当前输出已是蓝牙 */
    private fun audioAllowed(): Boolean = !bluetoothOnly || btOutputConnected

    /** 朗读被拦截时给 UI 的瞬时提示（普通窗口 + 贴底条共用，自动清空）；tick 用于让 snackbar 重复触发 */
    var audioHint by mutableStateOf<String?>(null)
    var audioHintTick by mutableStateOf(0)
    private var hintJob: Job? = null
    private var uiScope: CoroutineScope? = null

    var allWords by mutableStateOf<List<Word>>(emptyList())
    var loading by mutableStateOf(true)
    var bookTopicIds by mutableStateOf<Map<WordBook, Set<Int>>>(emptyMap())

    var currentIndex by mutableIntStateOf(0)
    var revealed by mutableStateOf(false)
    /** 自动朗读时用户手动翻面查看翻译 → 暂停翻下一词；翻回单词/例句面再继续 */
    private var autoPausedForReveal by mutableStateOf(false)

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
        val unknown = w.topicId in nr
        return when (normalizeFilterMode(mode)) {
            FILTER_MASTERED -> mastered && !unknown
            FILTER_UNKNOWN  -> unknown
            else            -> !mastered && !unknown // FILTER_UNMASTERED
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
    private companion object {
        const val BT_HINT = "🎧 请连接蓝牙耳机后再朗读"
        const val AUTO_GAP_MS = 700L   // 自动朗读：读完一个词到翻下一词之间的停顿
    }

    /** 显示一条瞬时朗读提示（2.5s 后自动清空），普通窗口与贴底条都会读取。 */
    fun showAudioHint(msg: String) {
        audioHint = msg
        audioHintTick++
        hintJob?.cancel()
        hintJob = uiScope?.launch { delay(2500); audioHint = null }
    }

    fun playWord(onComplete: (() -> Unit)? = null) {
        if (!audioAllowed()) { showAudioHint(BT_HINT); if (autoPlaying) stopAuto(); return }
        val bytes = zpkFiles?.let { wordAudioBytesFromZpk(it) }
        println("[AUD] playWord word=${currentWord?.word} bytes=${bytes?.size ?: -1} hasCb=${onComplete != null}")
        if (bytes != null) audio.play(bytes, "${currentWord?.word} word", onComplete)
        else { audio.stop(); onComplete?.invoke() }
    }

    fun playSentence(onComplete: (() -> Unit)? = null) {
        if (!audioAllowed()) { showAudioHint(BT_HINT); if (autoPlaying) stopAuto(); return }
        val key = sentences.getOrNull(sentenceIdx)?.audio
            ?: zpkMeta?.sentenceAudio?.takeIf { it.isNotBlank() }
        val bytes = key?.let { zpkFiles?.get(it) }
        println("[AUD] playSentence word=${currentWord?.word} key=$key bytes=${bytes?.size ?: -1} sIdx=$sentenceIdx sCount=${sentences.size} zpkKeys=${zpkFiles?.keys} hasCb=${onComplete != null}")
        if (bytes != null) audio.play(bytes, "${currentWord?.word} sent", onComplete)
        else { audio.stop(); onComplete?.invoke() }
    }

    /**
     * 按 [readMode] 朗读当前词（每次切词都会调一次）。自动朗读时按遍数重复、读完自动翻下一词循环；
     * 非自动时只读一遍（行为同改前）。词被切换则当前序列自动作废（collectLatest 会重启）。
     */
    fun autoPlay() {
        val tid = currentWord?.topicId ?: return
        // 自动朗读且开启“朗读时显示翻译” → 自动翻面（不标“不认识”，被动听不该污染状态）
        if (autoPlaying && showTransWhileRead) revealed = true
        val wc = if (autoPlaying) wordRepeat else 1
        val sc = if (autoPlaying) sentenceRepeat else 1
        val steps: List<Boolean> = when (readMode) {   // true=读单词 false=读例句
            READ_WORD_ONLY -> List(wc) { true }
            READ_SENTENCE_ONLY -> List(sc) { false }
            READ_BOTH_SENTENCE_FIRST -> List(sc) { false } + List(wc) { true }
            else -> List(wc) { true } + List(sc) { false }
        }
        fun runStep(i: Int) {
            if (currentWord?.topicId != tid) return        // 词已切换，放弃本序列
            if (i >= steps.size) { onAutoSequenceDone(tid); return }
            val next = { runStep(i + 1) }
            if (steps[i]) playWord(next) else playSentence(next)
        }
        runStep(0)
    }

    /** 一个词的朗读序列读完：若仍在自动朗读且未切词，停顿后翻下一词（goNext 回环 → 无缝循环）。 */
    private fun onAutoSequenceDone(tid: Int) {
        if (!autoPlaying || currentWord?.topicId != tid) return
        uiScope?.launch {
            delay(AUTO_GAP_MS)
            if (!autoPlaying || currentWord?.topicId != tid) return@launch
            // 用户手动翻面查看翻译中（非“朗读时显示翻译”模式）→ 暂停翻页，等翻回单词面再继续
            if (revealed && !showTransWhileRead) { autoPausedForReveal = true; return@launch }
            goNext()
        }
    }

    /** ▶/⏸ 切换自动朗读。 */
    fun toggleAuto() { if (autoPlaying) stopAuto() else startAuto() }

    /** 开始自动朗读：没蓝牙（仅蓝牙模式）则只提示不启动。 */
    fun startAuto() {
        if (autoPlaying) return
        if (!audioAllowed()) { showAudioHint(BT_HINT); return }
        autoPlaying = true
        autoPlay()
    }

    /** 停止自动朗读（失焦/没蓝牙/点⏸都会调）：打断当前朗读，需手动 ▶ 重启。 */
    fun stopAuto() {
        if (!autoPlaying) return
        autoPlaying = false
        autoPausedForReveal = false
        audio.stop()
    }

    fun updateWordRepeat(v: Int) { wordRepeat = v.coerceIn(1, 6); prefs.putInt("word_repeat_count", wordRepeat) }
    fun updateSentenceRepeat(v: Int) { sentenceRepeat = v.coerceIn(1, 3); prefs.putInt("sentence_repeat_count", sentenceRepeat) }
    fun updateShowTransWhileRead(v: Boolean) { showTransWhileRead = v; prefs.putInt("show_trans_while_read", if (v) 1 else 0) }

    // 离开当前词：仅隐藏翻译。
    // 「不认识」不再随浏览自动移除——只有点「斩」才会移出（移到已斩）。
    private fun leaveCurrentWord() {
        revealed = false
        autoPausedForReveal = false
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
        // 查看翻译 → 标记“不认识”
        if (revealed) {
            currentWord?.let { markNotRecognized(it.topicId) }
        } else if (autoPlaying && autoPausedForReveal) {
            // 翻回单词/例句面 → 恢复自动朗读，翻下一词
            autoPausedForReveal = false
            goNext()
        }
    }

    fun toggleMastered() {
        val w = currentWord ?: return
        val eff = overrides[w.topicId] ?: w.masteredInDb
        overrides = overrides.toMutableMap().also { it[w.topicId] = !eff }
        prefs.saveOverrides(overrides)
        // 点「斩」即离开「不认识」：同步从 nr 移出（反向取消斩不会重新加回）
        if (w.topicId in notRecognized) {
            notRecognized = notRecognized - w.topicId
            prefs.saveNotRecognized(notRecognized)
        }
    }

    fun updateReadMode(v: Int) { readMode = v; prefs.putInt("read_mode", v) }
    fun updateCrossBookFilter(v: Boolean) { crossBookFilter = v; prefs.putInt("cross_book_filter", if (v) 1 else 0) }
    fun updateAutoTransparent(v: Boolean) { autoTransparent = v; prefs.putInt("auto_transparent", if (v) 1 else 0) }
    /** 切换「仅蓝牙耳机才朗读」并持久化（key `bluetooth_only`）。 */
    fun updateBluetoothOnly(v: Boolean) { bluetoothOnly = v; prefs.putInt("bluetooth_only", if (v) 1 else 0) }

    // ── 响应式副作用（在后台 scope 启动一次，避免占用 UI 线程）──
    fun start(scope: CoroutineScope) {
        uiScope = scope
        // 后台轮询当前默认音频输出是否为蓝牙（缓存供朗读时即时判定，绝不在 EDT 探测）。
        // 首次立即探测，之后每 3s 一次；连/断蓝牙后最多 3s 内生效。
        scope.launch(Dispatchers.IO) {
            while (true) {
                // 开关关闭时无需探测（audioAllowed 已短路放行），省去每 3s 一次的子进程开销
                if (bluetoothOnly) btOutputConnected = MacAudioOutput.isBluetoothOutputActive()
                delay(3000)
            }
        }

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
