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
        private const val PREFS_NAME = "word_overrides"
        private const val READ_MARKS_PREFS = "read_marks"
        private const val KEY_READ_IDS = "read_topic_ids"

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

    // 已读单词 topicId 集合（用于列表页绿色标记，轮次刷新时清空）
    // 使用 MMKV 存储，mmap 机制保证写入即持久化，不怕进程被杀
    private val mmkv = MMKV.mmkvWithID(READ_MARKS_PREFS)
    private val _readTopicIds = MutableStateFlow<Set<Int>>(loadReadMarks())
    val readTopicIds: StateFlow<Set<Int>> = _readTopicIds

    private fun loadReadMarks(): Set<Int> {
        val stored = mmkv.decodeString(KEY_READ_IDS)
        if (stored.isNullOrBlank()) return emptySet()
        return stored.split(",").mapNotNull { it.toIntOrNull() }.toSet()
    }

    private fun saveReadMarks(ids: Set<Int>) {
        val value = if (ids.isEmpty()) "" else ids.joinToString(",")
        mmkv.encode(KEY_READ_IDS, value)
    }

    fun markRead(topicId: Int) {
        val updated = _readTopicIds.value + topicId
        _readTopicIds.value = updated
        saveReadMarks(updated)
    }

    fun clearReadMarks() {
        _readTopicIds.value = emptySet()
        mmkv.encode(KEY_READ_IDS, "")
    }

    private val wordCache = mutableMapOf<WordBook, List<Word>>()

    init {
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        @Suppress("UNCHECKED_CAST")
        _overrides.value = prefs.all.entries.mapNotNull { (k, v) ->
            k.toIntOrNull()?.let { id -> id to (v as Boolean) }
        }.toMap()
    }

    /** 加载指定词书。forceReload=true 时清除缓存重新读 DB */
    suspend fun loadWords(book: WordBook, forceReload: Boolean = false) {
        if (!forceReload && wordCache.containsKey(book)) {
            _loadState.value = LoadState.Success(wordCache[book]!!)
            return
        }
        if (_loadState.value is LoadState.Loading) return
        _loadState.value = LoadState.Loading

        withContext(Dispatchers.IO) {
            try {
                ensureAssetsExtracted()

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

    fun toggleMastered(topicId: Int, currentEffective: Boolean) {
        val new = !currentEffective
        _overrides.value = _overrides.value.toMutableMap().also { it[topicId] = new }
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(topicId.toString(), new).apply()
    }

    // ── 进度同步：导出/导入 ─────────────────────────────────────────

    fun exportProgress(): String {
        val json = JSONObject()
        // 已斩/未斩
        val ov = JSONObject()
        _overrides.value.forEach { (k, v) -> ov.put(k.toString(), v) }
        json.put("overrides", ov)
        // 已读标记
        val readArray = JSONArray()
        _readTopicIds.value.forEach { readArray.put(it) }
        json.put("readTopicIds", readArray)
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
            val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val editor = prefs.edit().clear()
            map.forEach { (k, v) -> editor.putBoolean(k.toString(), v) }
            editor.commit()
        }
        // 覆盖已读标记
        val readArray = json.optJSONArray("readTopicIds")
        if (readArray != null) {
            val ids = mutableSetOf<Int>()
            for (i in 0 until readArray.length()) ids.add(readArray.getInt(i))
            _readTopicIds.value = ids
            saveReadMarks(ids)
        }
        // 清除词缓存，下次打开列表时重新加载
        wordCache.clear()
    }
}
