package com.example.testapplication.vocab

import android.content.Context
import android.database.sqlite.SQLiteDatabase
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
