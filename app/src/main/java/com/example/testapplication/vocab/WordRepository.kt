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

class WordRepository private constructor(private val appContext: Context) {

    companion object {
        private const val PREFS_NAME = "word_overrides"
        private const val BASE_PATH =
            "/sdcard/Android/data/com.jiongji.andriod.card/files/baicizhan"

        @Volatile
        private var INSTANCE: WordRepository? = null

        fun getInstance(context: Context): WordRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: WordRepository(context.applicationContext).also { INSTANCE = it }
            }
    }

    private val _loadState = MutableStateFlow<LoadState>(LoadState.Idle)
    val loadState: StateFlow<LoadState> = _loadState

    private val _overrides = MutableStateFlow<Map<Int, Boolean>>(emptyMap())
    val overrides: StateFlow<Map<Int, Boolean>> = _overrides

    init {
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        @Suppress("UNCHECKED_CAST")
        val saved = prefs.all.entries.mapNotNull { (key, value) ->
            key.toIntOrNull()?.let { id -> id to (value as Boolean) }
        }.toMap()
        _overrides.value = saved
    }

    suspend fun loadWords() {
        if (_loadState.value is LoadState.Loading) return
        _loadState.value = LoadState.Loading
        withContext(Dispatchers.IO) {
            try {
                val roadmapFile = File(appContext.cacheDir, "road_map_409.baicizhan")
                val lookupFile = File(appContext.cacheDir, "lookup.db")
                val statusFile = File(appContext.cacheDir, "bczproblem.db")

                copyViaRoot("$BASE_PATH/roadmap/road_map_409.baicizhan", roadmapFile)
                copyViaRoot("$BASE_PATH/lookup.db", lookupFile)
                copyViaRoot("$BASE_PATH/baicizhantopicproblem.db", statusFile)

                if (!roadmapFile.exists() || roadmapFile.length() == 0L)
                    throw Exception("路线图文件不存在，请确认百词斩数据库路径")
                if (!lookupFile.exists() || lookupFile.length() == 0L)
                    throw Exception("lookup.db 不存在")
                if (!statusFile.exists() || statusFile.length() == 0L)
                    throw Exception("baicizhantopicproblem.db 不存在")

                val topicIds = parseRoadmap(roadmapFile)
                val topicIdSet = topicIds.toHashSet()
                val masteryMap = readMasteryStatus(statusFile)
                val wordMap = readWordDetails(lookupFile, topicIdSet)

                val words = topicIds.mapNotNull { id ->
                    val d = wordMap[id] ?: return@mapNotNull null
                    val obn = masteryMap.getOrDefault(id, 1.0)
                    Word(
                        topicId = id,
                        word = d.word,
                        accent = d.accent,
                        meanCn = d.meanCn,
                        sentence = d.sentence,
                        sentenceTrans = d.sentenceTrans,
                        masteredInDb = obn < 1.0
                    )
                }.sortedBy { it.word.lowercase() }

                _loadState.value = LoadState.Success(words)
            } catch (e: Exception) {
                _loadState.value = LoadState.Error(e.message ?: "加载失败")
            }
        }
    }

    private data class WordDetails(
        val word: String, val accent: String, val meanCn: String,
        val sentence: String, val sentenceTrans: String
    )

    private fun copyViaRoot(src: String, dst: File) {
        RootUtils.execRootCmd("cp \"$src\" \"${dst.absolutePath}\"")
        RootUtils.execRootCmd("chmod 666 \"${dst.absolutePath}\"")
    }

    private fun parseRoadmap(file: File): List<Int> {
        val json = file.readText()
        val array = JSONArray(json)
        return (0 until array.length()).map { array.getJSONObject(it).getInt("topic_id") }
    }

    private fun readMasteryStatus(file: File): Map<Int, Double> {
        val map = mutableMapOf<Int, Double>()
        val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            val cursor = db.rawQuery(
                "SELECT topic_id, topic_obn FROM ts_learn_offline_dotopic_sync_ids_409", null
            )
            while (cursor.moveToNext()) {
                map[cursor.getInt(0)] = cursor.getDouble(1)
            }
            cursor.close()
        } finally {
            db.close()
        }
        return map
    }

    private fun readWordDetails(file: File, topicIds: Set<Int>): Map<Int, WordDetails> {
        val tables = listOf(
            "dict_a_b", "dict_c", "dict_d_f", "dict_g_k",
            "dict_l_o", "dict_p_r", "dict_s", "dict_t_z"
        )
        val result = mutableMapOf<Int, WordDetails>()
        val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            for (table in tables) {
                val cursor = db.rawQuery(
                    "SELECT topic_id, word, accent, mean_cn, sentence, sentence_trans FROM $table",
                    null
                )
                while (cursor.moveToNext()) {
                    val id = cursor.getInt(0)
                    if (id !in topicIds) continue
                    result[id] = WordDetails(
                        word = cursor.getString(1) ?: "",
                        accent = cursor.getString(2) ?: "",
                        meanCn = cursor.getString(3) ?: "",
                        sentence = cursor.getString(4) ?: "",
                        sentenceTrans = cursor.getString(5) ?: ""
                    )
                }
                cursor.close()
            }
        } finally {
            db.close()
        }
        return result
    }

    fun effectiveMastered(topicId: Int, dbState: Boolean, overrides: Map<Int, Boolean>): Boolean =
        overrides.getOrDefault(topicId, dbState)

    fun toggleMastered(topicId: Int, currentEffective: Boolean) {
        val newState = !currentEffective
        _overrides.value = _overrides.value.toMutableMap().also { it[topicId] = newState }
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(topicId.toString(), newState).apply()
    }
}
