package com.example.testapplication.vocab

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.example.testapplication.RootUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
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
        private const val SDCARD_STATUS_DB =
            "/sdcard/Android/data/com.jiongji.andriod.card/files/baicizhan/baicizhantopicproblem.db"

        @Volatile
        private var INSTANCE: WordRepository? = null

        fun getInstance(context: Context): WordRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: WordRepository(context.applicationContext).also { INSTANCE = it }
            }
    }

    // filesDir/baicizhan/ 是运行时数据目录，assets 内容首次运行时复制过来
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

    // 每本词书加载后缓存，切换词书无需重复 IO
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

                val topicIds  = parseRoadmap(roadmap)
                val masteryMap = readMasteryStatus(book)
                val wordMap   = readWordDetails(topicIds.toHashSet())

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
     * 从百词斩 sdcard 目录同步 baicizhantopicproblem.db（需要 Root）。
     * 同步成功后清空缓存，调用方应重新调用 loadWords()。
     */
    suspend fun syncFromBaicizhan() {
        _syncState.value = SyncState.Syncing
        withContext(Dispatchers.IO) {
            try {
                baseDir.mkdirs()
                RootUtils.execRootCmd("cp \"$SDCARD_STATUS_DB\" \"${statusFile.absolutePath}\"")
                RootUtils.execRootCmd("chmod 666 \"${statusFile.absolutePath}\"")

                if (!statusFile.exists() || statusFile.length() == 0L)
                    throw Exception("同步失败，确认百词斩已安装且本应用有 Root 权限")

                wordCache.clear()   // 清缓存，下次 loadWords 重新计算状态
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

    /** 首次运行时把 assets 里的 DB 复制到 filesDir（后续直接用本地文件） */
    private fun ensureAssetsExtracted() {
        baseDir.mkdirs()
        File(baseDir, "roadmap").mkdirs()

        copyAssetIfMissing("baicizhan/lookup.db", lookupFile)
        copyAssetIfMissing("baicizhan/baicizhantopicproblem.db", statusFile)

        // 复制所有 roadmap 文件
        try {
            appContext.assets.list("baicizhan/roadmap")?.forEach { name ->
                copyAssetIfMissing("baicizhan/roadmap/$name", File(baseDir, "roadmap/$name"))
            }
        } catch (_: Exception) {}
    }

    private fun copyAssetIfMissing(assetPath: String, dest: File) {
        if (dest.exists()) return
        try {
            appContext.assets.open(assetPath).use { src ->
                dest.outputStream().use { dst -> src.copyTo(dst) }
            }
        } catch (_: Exception) { /* asset 不存在则跳过 */ }
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
                while (c.moveToNext()) map[c.getInt(0)] = c.getDouble(1)
                c.close()
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
        val tables = listOf("dict_a_b", "dict_c", "dict_d_f", "dict_g_k",
                            "dict_l_o", "dict_p_r", "dict_s", "dict_t_z")
        val result = mutableMapOf<Int, WordDetails>()
        val db = SQLiteDatabase.openDatabase(lookupFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            for (table in tables) {
                try {
                    val c = db.rawQuery(
                        "SELECT topic_id, word, accent, mean_cn, sentence, sentence_trans FROM $table", null)
                    while (c.moveToNext()) {
                        val id = c.getInt(0)
                        if (id !in topicIds) continue
                        result[id] = WordDetails(c.getString(1) ?: "", c.getString(2) ?: "",
                                                 c.getString(3) ?: "", c.getString(4) ?: "",
                                                 c.getString(5) ?: "")
                    }
                    c.close()
                } catch (_: Exception) {}
            }
        } finally { db.close() }
        return result
    }

    // ── 公开：掌握状态管理 ─────────────────────────────────────────

    fun effectiveMastered(topicId: Int, dbState: Boolean, overrides: Map<Int, Boolean>): Boolean =
        overrides.getOrDefault(topicId, dbState)

    fun toggleMastered(topicId: Int, currentEffective: Boolean) {
        val new = !currentEffective
        _overrides.value = _overrides.value.toMutableMap().also { it[topicId] = new }
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(topicId.toString(), new).apply()
    }
}
