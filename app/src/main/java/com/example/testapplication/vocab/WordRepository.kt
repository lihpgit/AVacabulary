package com.example.testapplication.vocab

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

sealed class LoadState {
    object Idle : LoadState()
    object Loading : LoadState()
    data class Success(val words: List<Word>) : LoadState()
    data class Error(val message: String) : LoadState()
}

sealed class SyncState {
    object Idle : SyncState()
    object Syncing : SyncState()
    object Success : SyncState()
    data class Error(val message: String) : SyncState()
}

class WordRepository private constructor(private val appContext: Context) {

    companion object {
        private const val PREFS_NAME = "word_overrides"     // MMKV ID
        private const val KEY_OVERRIDES = "overrides_json"  // MMKV key，存 JSON 字符串
        private const val READ_MARKS_PREFS = "read_marks"

        const val FILTER_UNMASTERED = "unmastered"
        const val FILTER_MASTERED   = "mastered"

        // 已读标记按词书 + 过滤类型隔离：key = "read_topic_ids_{bookId}_{filterType}"
        private fun readMarksKey(book: WordBook, filterType: String) =
            "read_topic_ids_${book.id}_$filterType"
        // 旧版 key（只按词书隔离），仅用于一次性清理
        private fun legacyReadMarksKey(book: WordBook) = "read_topic_ids_${book.id}"

        @Volatile
        private var INSTANCE: WordRepository? = null

        fun getInstance(context: Context): WordRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: WordRepository(context.applicationContext).also { INSTANCE = it }
            }
    }

    private val baseDir    get() = File(appContext.filesDir, "baicizhan")
    private val lookupFile get() = File(baseDir, "lookup.db")
    private val statusFile get() = File(baseDir, "baicizhantopicproblem.db")
    private fun roadmapFile(book: WordBook) = File(baseDir, "roadmap/${book.roadmapFileName}")

    private val _loadState = MutableStateFlow<LoadState>(LoadState.Idle)
    val loadState: StateFlow<LoadState> = _loadState

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState

    private val _overrides = MutableStateFlow<Map<Int, Boolean>>(emptyMap())
    val overrides: StateFlow<Map<Int, Boolean>> = _overrides

    // 已读单词 topicId 集合（用于列表页绿色标记，轮次刷新时清空）按词书隔离
    // 使用 MMKV 存储，mmap 机制保证写入即持久化，不怕进程被杀
    private val mmkv = MMKV.mmkvWithID(READ_MARKS_PREFS)
    // 已斩/未斩 override 存储（MMKV，整个 Map 序列化为 JSON 字符串）
    private val mmkvOverrides = MMKV.mmkvWithID(PREFS_NAME)

    // 当前加载的词书（null = 尚未加载）
    private var _currentBook: WordBook? = null
    val currentBook: WordBook? get() = _currentBook

    // 当前词书的已读标记，按 未斩/已斩 分桶，切换词书时替换
    private val _readTopicIdsUnmastered = MutableStateFlow<Set<Int>>(emptySet())
    val readTopicIdsUnmastered: StateFlow<Set<Int>> = _readTopicIdsUnmastered

    private val _readTopicIdsMastered = MutableStateFlow<Set<Int>>(emptySet())
    val readTopicIdsMastered: StateFlow<Set<Int>> = _readTopicIdsMastered

    /** 按 filterType 取对应桶的已读标记 StateFlow */
    fun readTopicIds(filterType: String): StateFlow<Set<Int>> =
        if (filterType == FILTER_MASTERED) _readTopicIdsMastered else _readTopicIdsUnmastered

    // topicId 在所有词书中出现的次数（1=仅当前词书，2=另有1本，3=另有2本）
    private val _crossBookCounts = MutableStateFlow<Map<Int, Int>>(emptyMap())
    val crossBookCounts: StateFlow<Map<Int, Int>> = _crossBookCounts

    // 各词书的 topicId 集合（用于跨词书过滤）
    private val _bookTopicIds = MutableStateFlow<Map<WordBook, Set<Int>>>(emptyMap())
    val bookTopicIds: StateFlow<Map<WordBook, Set<Int>>> = _bookTopicIds

    // 全词书去重后的 (未斩数, 已斩数)
    private val _globalFilteredCounts = MutableStateFlow<Pair<Int, Int>?>(null)
    val globalFilteredCounts: StateFlow<Pair<Int, Int>?> = _globalFilteredCounts

    private fun refreshCrossBookCounts() {
        val counts = mutableMapOf<Int, Int>()
        val byBook = mutableMapOf<WordBook, Set<Int>>()
        for (book in WordBook.entries) {
            val file = roadmapFile(book)
            if (!file.exists()) continue
            try {
                val ids = parseRoadmap(file)
                byBook[book] = ids.toSet()
                for (id in ids) counts[id] = (counts[id] ?: 0) + 1
            } catch (_: Exception) {}
        }
        _crossBookCounts.value = counts
        _bookTopicIds.value = byBook
    }

    /** 从 DB 读取全部词书的掌握状态，去重后统计未斩/已斩总数 */
    suspend fun refreshGlobalFilteredCounts() {
        withContext(Dispatchers.IO) {
            ensureAssetsExtracted()
            if (_bookTopicIds.value.isEmpty()) refreshCrossBookCounts()

            val ov = _overrides.value
            val seen = mutableSetOf<Int>()
            var unmasteredCount = 0
            var masteredCount = 0

            // 按优先级遍历（高考 > 四级 > 六级），每个 topicId 只计一次
            for (book in WordBook.entries) {
                val ids = _bookTopicIds.value[book] ?: continue
                val status = readMasteryStatus(book)
                for (id in ids) {
                    if (seen.add(id)) {
                        val dbMastered = (status.getOrDefault(id, 1.0)) < 1.0
                        if (effectiveMastered(id, dbMastered, ov)) masteredCount++
                        else unmasteredCount++
                    }
                }
            }
            _globalFilteredCounts.value = unmasteredCount to masteredCount
        }
    }

    private fun loadReadMarks(book: WordBook, filterType: String): Set<Int> {
        val stored = mmkv.decodeString(readMarksKey(book, filterType))
        if (stored.isNullOrBlank()) return emptySet()
        return stored.split(",").mapNotNull { it.toIntOrNull() }.toSet()
    }

    /** 一次性清理旧的"按词书"key（旧版本未斩/已斩共用一份，会互相污染） */
    private fun cleanupLegacyReadMarks(book: WordBook) {
        mmkv.removeValueForKey(legacyReadMarksKey(book))
        // 更早期的全局 key
        mmkv.removeValueForKey("read_topic_ids")
    }

    private fun saveReadMarks(ids: Set<Int>, filterType: String) {
        val book = _currentBook ?: return
        val value = if (ids.isEmpty()) "" else ids.joinToString(",")
        mmkv.encode(readMarksKey(book, filterType), value)
    }

    private fun bucketStateFlow(filterType: String): MutableStateFlow<Set<Int>> =
        if (filterType == FILTER_MASTERED) _readTopicIdsMastered else _readTopicIdsUnmastered

    fun markRead(topicId: Int, filterType: String) {
        val flow = bucketStateFlow(filterType)
        val updated = flow.value + topicId
        flow.value = updated
        saveReadMarks(updated, filterType)
    }

    fun clearReadMarks(filterType: String) {
        val book = _currentBook ?: return
        bucketStateFlow(filterType).value = emptySet()
        mmkv.encode(readMarksKey(book, filterType), "")
    }

    private val wordCache = mutableMapOf<WordBook, List<Word>>()

    init {
        // 从 MMKV 加载已斩/未斩 override（JSON 字符串 → Map）
        val stored = mmkvOverrides.decodeString(KEY_OVERRIDES)
        if (!stored.isNullOrBlank()) {
            try {
                val jsonObj = JSONObject(stored)
                val map = mutableMapOf<Int, Boolean>()
                val keys = jsonObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    key.toIntOrNull()?.let { id -> map[id] = jsonObj.getBoolean(key) }
                }
                _overrides.value = map
            } catch (_: Exception) {}
        }
    }

    private fun reloadBuckets(book: WordBook) {
        cleanupLegacyReadMarks(book)
        _readTopicIdsUnmastered.value = loadReadMarks(book, FILTER_UNMASTERED)
        _readTopicIdsMastered.value   = loadReadMarks(book, FILTER_MASTERED)
    }

    /** 加载指定词书。forceReload=true 时清除缓存重新读 DB */
    suspend fun loadWords(book: WordBook, forceReload: Boolean = false) {
        if (!forceReload && wordCache.containsKey(book)) {
            _currentBook = book
            reloadBuckets(book)
            _loadState.value = LoadState.Success(wordCache[book]!!)
            return
        }
        if (_loadState.value is LoadState.Loading) return
        _loadState.value = LoadState.Loading

        withContext(Dispatchers.IO) {
            try {
                ensureAssetsExtracted()
                if (_crossBookCounts.value.isEmpty()) refreshCrossBookCounts()

                val roadmap = roadmapFile(book)
                if (!roadmap.exists())
                    throw Exception("${book.displayName}词书未找到\n请将 ${book.roadmapFileName} 放入 assets/baicizhan/roadmap/")
                if (!lookupFile.exists())
                    throw Exception("lookup.db 未找到，请将其放入 assets/baicizhan/")

                val topicIds   = parseRoadmap(roadmap)
                val masteryMap = readMasteryStatus(book)
                val wordMap    = readWordDetails(topicIds.toHashSet())

                val words = topicIds.mapNotNull { id ->
                    val d = wordMap[id] ?: return@mapNotNull null
                    val obn = masteryMap.getOrDefault(id, 1.0)
                    Word(
                        topicId       = id,
                        word          = d.word,
                        accent        = d.accent,
                        meanCn        = d.meanCn,
                        sentence      = d.sentence,
                        sentenceTrans = d.sentenceTrans,
                        masteredInDb  = obn < 1.0
                    )
                }.sortedBy { it.word.lowercase() }

                wordCache[book] = words
                _currentBook = book
                reloadBuckets(book)
                _loadState.value = LoadState.Success(words)
            } catch (e: Exception) {
                _loadState.value = LoadState.Error(e.message ?: "加载失败")
            }
        }
    }

    /**
     * 把 assets 里的数据库文件强制重新覆盖到 filesDir。
     * 适用场景：将新的 DB 文件放入 assets 重新打包安装后，点此按钮刷新。
     */
    suspend fun syncFromAssets() {
        _syncState.value = SyncState.Syncing
        withContext(Dispatchers.IO) {
            try {
                baseDir.mkdirs()
                File(baseDir, "roadmap").mkdirs()

                forceCopyAsset("baicizhan/lookup.db", lookupFile)
                forceCopyAsset("baicizhan/baicizhantopicproblem.db", statusFile)

                try {
                    appContext.assets.list("baicizhan/roadmap")?.forEach { name ->
                        forceCopyAsset("baicizhan/roadmap/$name", File(baseDir, "roadmap/$name"))
                    }
                } catch (_: Exception) {}

                if (!lookupFile.exists() || lookupFile.length() == 0L)
                    throw Exception("assets/baicizhan/lookup.db 不存在或为空，请先将数据库文件放入 assets 目录")

                wordCache.clear()
                _crossBookCounts.value = emptyMap() // 强制下次 loadWords 重新统计
                _bookTopicIds.value = emptyMap()
                _syncState.value = SyncState.Success
            } catch (e: Exception) {
                _syncState.value = SyncState.Error(e.message ?: "同步失败")
            }
        }
    }

    fun resetSyncState() {
        _syncState.value = SyncState.Idle
    }

    // ── 内部：资源提取 ──────────────────────────────────────────────

    /** 首次运行（或文件缺失）时从 assets 复制到 filesDir */
    private fun ensureAssetsExtracted() {
        baseDir.mkdirs()
        File(baseDir, "roadmap").mkdirs()

        // 文件存在且大于 0 才跳过，防止旧版本遗留空文件
        copyAssetIfMissing("baicizhan/lookup.db", lookupFile)
        copyAssetIfMissing("baicizhan/baicizhantopicproblem.db", statusFile)

        try {
            appContext.assets.list("baicizhan/roadmap")?.forEach { name ->
                copyAssetIfMissing("baicizhan/roadmap/$name", File(baseDir, "roadmap/$name"))
            }
        } catch (_: Exception) {}
    }

    /** 仅当目标文件不存在或为空时才复制 */
    private fun copyAssetIfMissing(assetPath: String, dest: File) {
        if (dest.exists() && dest.length() > 0L) return
        forceCopyAsset(assetPath, dest)
    }

    /** 无条件从 assets 覆盖复制 */
    private fun forceCopyAsset(assetPath: String, dest: File) {
        try {
            appContext.assets.open(assetPath).use { src ->
                dest.outputStream().use { dst -> src.copyTo(dst) }
            }
        } catch (_: Exception) {}
    }

    // ── 内部：DB 读取 ──────────────────────────────────────────────

    private fun parseRoadmap(file: File): List<Int> {
        val arr = JSONArray(file.readText())
        return (0 until arr.length()).map { arr.getJSONObject(it).getInt("topic_id") }
    }

    private fun readMasteryStatus(book: WordBook): Map<Int, Double> {
        if (!statusFile.exists()) return emptyMap()
        val map = mutableMapOf<Int, Double>()
        try {
            val db = SQLiteDatabase.openDatabase(statusFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            try {
                val c = db.rawQuery("SELECT topic_id, topic_obn FROM ${book.statusTableName}", null)
                try {
                    while (c.moveToNext()) map[c.getInt(0)] = c.getDouble(1)
                } finally { c.close() }
            } finally { db.close() }
        } catch (_: Exception) {}
        return map
    }

    private data class WordDetails(
        val word: String, val accent: String, val meanCn: String,
        val sentence: String, val sentenceTrans: String
    )

    private fun readWordDetails(topicIds: Set<Int>): Map<Int, WordDetails> {
        if (!lookupFile.exists()) return emptyMap()
        val result = mutableMapOf<Int, WordDetails>()
        val db = SQLiteDatabase.openDatabase(lookupFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            // 动态发现 dict_ 开头的表（排除 dict_bcz 等非词典表）
            val allTables = mutableListOf<String>()
            val tc = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null)
            try {
                while (tc.moveToNext()) {
                    val name = tc.getString(0)
                    if (name.startsWith("dict_") && !name.startsWith("dict_bcz")) allTables.add(name)
                }
            } finally { tc.close() }

            for (table in allTables) {
                try {
                    // 用 PRAGMA table_info 动态检测实际存在的列
                    val cols = mutableListOf<String>()
                    val pi = db.rawQuery("PRAGMA table_info($table)", null)
                    try {
                        while (pi.moveToNext()) cols.add(pi.getString(1))
                    } finally { pi.close() }

                    val idCol   = cols.firstOrNull { it in listOf("topic_id", "id", "word_id") } ?: continue
                    val wordCol = cols.firstOrNull { it in listOf("word", "word_name", "en_word") } ?: continue
                    val meanCol = cols.firstOrNull { it in listOf("mean_cn", "chinese", "cn_mean", "trans", "mean") }
                    val accCol  = cols.firstOrNull { it in listOf("accent", "phonetic", "ipa") }
                    val sentCol = cols.firstOrNull { it in listOf("sentence", "example", "eg_sentence") }
                    val stCol   = cols.firstOrNull { it in listOf("sentence_trans", "eg_trans", "sentence_cn") }

                    // 只 SELECT 实际存在的列
                    val selCols = mutableListOf(idCol, wordCol)
                    meanCol?.let { selCols.add(it) }
                    accCol?.let  { selCols.add(it) }
                    sentCol?.let { selCols.add(it) }
                    stCol?.let   { selCols.add(it) }

                    val c = db.rawQuery("SELECT ${selCols.joinToString(",")} FROM $table", null)
                    try {
                        while (c.moveToNext()) {
                            val id = c.getInt(0)
                            if (id !in topicIds) continue
                            var idx = 2 // 0=id, 1=word
                            val mean = if (meanCol != null) c.getString(idx++).orEmpty() else ""
                            val acc  = if (accCol  != null) c.getString(idx++).orEmpty() else ""
                            val sent = if (sentCol != null) c.getString(idx++).orEmpty() else ""
                            val st   = if (stCol   != null) c.getString(idx++).orEmpty() else ""
                            result[id] = WordDetails(
                                word = c.getString(1).orEmpty(), accent = acc,
                                meanCn = mean, sentence = sent, sentenceTrans = st
                            )
                        }
                    } finally { c.close() }
                } catch (_: Exception) {}
            }
        } finally { db.close() }
        return result
    }

    // ── zpk 资源包 ────────────────────────────────────────────────

    sealed class ZpkSource {
        data class FileSystem(val file: File) : ZpkSource()
        data class Asset(val path: String) : ZpkSource()
    }

    @Volatile
    private var zpkIndex: Map<Int, ZpkSource>? = null

    /** 获取 topicId → zpk 文件的索引，首次调用时自动构建 */
    private fun getZpkIndex(): Map<Int, ZpkSource> {
        zpkIndex?.let { return it }
        synchronized(this) {
            zpkIndex?.let { return it }
            return buildZpkIndex().also { zpkIndex = it }
        }
    }

    private fun buildZpkIndex(): Map<Int, ZpkSource> {
        val index = mutableMapOf<Int, ZpkSource>()

        // 1. 扫描 filesDir/baicizhan/zpack/ 下的所有 bookId/subDir/
        val fsRoot = File(baseDir, "zpack")
        if (fsRoot.exists()) {
            fsRoot.listFiles()?.filter { it.isDirectory }?.forEach { bookDir ->
                bookDir.listFiles()?.filter { it.isDirectory }?.forEach { subDir ->
                    subDir.listFiles { f -> f.extension == "zpk" }?.forEach { zpk ->
                        extractTopicId(zpk.name)?.let { id -> index[id] = ZpkSource.FileSystem(zpk) }
                    }
                }
            }
        }

        // 2. 扫描 assets/baicizhan/zpack/ （不提取，运行时按需读取）
        try {
            val bookDirs = appContext.assets.list("baicizhan/zpack") ?: emptyArray()
            for (bookDir in bookDirs) {
                val subDirs = appContext.assets.list("baicizhan/zpack/$bookDir") ?: continue
                for (subDir in subDirs) {
                    val files = appContext.assets.list("baicizhan/zpack/$bookDir/$subDir") ?: continue
                    for (fileName in files) {
                        if (!fileName.endsWith(".zpk")) continue
                        val topicId = extractTopicId(fileName) ?: continue
                        if (topicId !in index) {
                            index[topicId] = ZpkSource.Asset("baicizhan/zpack/$bookDir/$subDir/$fileName")
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        return index
    }

    /** 从文件名 zp_{topicId}_409_0_{ts}.zpk 中提取 topicId */
    private fun extractTopicId(fileName: String): Int? =
        fileName.split("_").getOrNull(1)?.toIntOrNull()

    /** 解析指定 topicId 的 zpk，返回文件名 → 字节内容 的映射 */
    fun readZpk(topicId: Int): Map<String, ByteArray>? {
        val source = getZpkIndex()[topicId] ?: return null
        val data = when (source) {
            is ZpkSource.FileSystem -> source.file.readBytes()
            is ZpkSource.Asset -> appContext.assets.open(source.path).use { it.readBytes() }
        }
        return ZpakParser.parse(data)
    }

    /** zpk 索引中的单词数量（用于 UI 提示） */
    fun zpkCount(): Int = getZpkIndex().size

    // ── 公开：掌握状态管理 ─────────────────────────────────────────

    fun effectiveMastered(topicId: Int, dbState: Boolean, overrides: Map<Int, Boolean>): Boolean =
        overrides.getOrDefault(topicId, dbState)

    private fun saveOverrides(map: Map<Int, Boolean>) {
        val jsonObj = JSONObject()
        map.forEach { (k, v) -> jsonObj.put(k.toString(), v) }
        mmkvOverrides.encode(KEY_OVERRIDES, jsonObj.toString())
    }

    fun toggleMastered(topicId: Int, currentEffective: Boolean) {
        val new = !currentEffective
        val updated = _overrides.value.toMutableMap().also { it[topicId] = new }
        _overrides.value = updated
        saveOverrides(updated)
    }

    // ── 进度同步：导出/导入 ─────────────────────────────────────────

    fun exportProgress(): String {
        val json = JSONObject()
        // 已斩/未斩（全局，topicId 跨词书唯一）
        val ov = JSONObject()
        _overrides.value.forEach { (k, v) -> ov.put(k.toString(), v) }
        json.put("overrides", ov)
        // 已读标记：按词书 + 过滤类型分别导出，key = "readTopicIds_{bookId}_{filterType}"
        for (book in WordBook.entries) {
            for (filter in listOf(FILTER_UNMASTERED, FILTER_MASTERED)) {
                val ids = if (book == _currentBook) bucketStateFlow(filter).value
                          else loadReadMarks(book, filter)
                val arr = JSONArray()
                ids.forEach { arr.put(it) }
                json.put("readTopicIds_${book.id}_$filter", arr)
            }
        }
        return json.toString()
    }

    fun importProgress(jsonStr: String) {
        val json = JSONObject(jsonStr)
        // 覆盖已斩/未斩
        val ov = json.optJSONObject("overrides")
        if (ov != null) {
            val map = mutableMapOf<Int, Boolean>()
            val keys = ov.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                key.toIntOrNull()?.let { id -> map[id] = ov.getBoolean(key) }
            }
            _overrides.value = map
            saveOverrides(map)
        }
        // 覆盖各词书 + 过滤类型的已读标记
        for (book in WordBook.entries) {
            for (filter in listOf(FILTER_UNMASTERED, FILTER_MASTERED)) {
                val arr = json.optJSONArray("readTopicIds_${book.id}_$filter") ?: continue
                val ids = mutableSetOf<Int>()
                for (i in 0 until arr.length()) ids.add(arr.getInt(i))
                val value = if (ids.isEmpty()) "" else ids.joinToString(",")
                mmkv.encode(readMarksKey(book, filter), value)
                if (book == _currentBook) bucketStateFlow(filter).value = ids
            }
        }
        // 清除词缓存，下次打开列表时重新加载
        wordCache.clear()
    }
}
