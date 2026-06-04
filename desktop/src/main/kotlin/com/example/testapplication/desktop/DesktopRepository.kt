package com.example.testapplication.desktop

import com.example.testapplication.vocab.Word
import com.example.testapplication.vocab.WordBook
import com.example.testapplication.vocab.ZpakParser
import org.json.JSONArray
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * 桌面端数据仓库：把 Android `WordRepository` 的 DB/zpk 读取逻辑移植到 JDBC + 文件系统。
 * 只读 assets 目录里的 DB 和 zpack，不修改原文件。
 */
class DesktopRepository {

    private val wordCache = mutableMapOf<WordBook, List<Word>>()

    // ── 公开 API ────────────────────────────────────────────────

    /** 加载某词书的全部单词（按字母排序），带缓存 */
    fun loadWords(book: WordBook): List<Word> {
        wordCache[book]?.let { return it }
        val roadmap = DesktopPaths.roadmap(book)
        require(roadmap.exists()) { "未找到词书路线图: ${roadmap.absolutePath}" }
        require(DesktopPaths.lookupDb.exists()) { "未找到 lookup.db: ${DesktopPaths.lookupDb.absolutePath}" }

        val topicIds   = parseRoadmap(roadmap)
        val mastery    = readMasteryStatus(book)
        val details    = readWordDetails(topicIds.toHashSet())

        val words = topicIds.mapNotNull { id ->
            val d = details[id] ?: return@mapNotNull null
            val obn = mastery.getOrDefault(id, 1.0)
            Word(
                topicId = id,
                word = d.word,
                accent = d.accent,
                meanCn = d.meanCn,
                sentence = d.sentence,
                sentenceTrans = d.sentenceTrans,
                masteredInDb = obn < 1.0,
            )
        }.sortedBy { it.word.lowercase() }

        wordCache[book] = words
        return words
    }

    // 已解析 zpk 的 LRU 缓存（按访问顺序，最多 24 条；每条 zpk 数百 KB）
    private val zpkCache = object : LinkedHashMap<Int, Map<String, ByteArray>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Map<String, ByteArray>>) = size > 24
    }

    /** 读取某 topicId 的 zpk，返回 文件名 → 字节内容（带 LRU 缓存） */
    fun readZpk(topicId: Int): Map<String, ByteArray>? {
        synchronized(zpkCache) { zpkCache[topicId]?.let { return it } }
        val file = zpkIndex()[topicId] ?: return null
        val parsed = ZpakParser.parse(file.readBytes())
        synchronized(zpkCache) { zpkCache[topicId] = parsed }
        return parsed
    }

    /** 预取某 topicId 的 zpk 进缓存（后台调用，让下一次 readZpk 命中缓存） */
    fun prefetchZpk(topicId: Int) {
        synchronized(zpkCache) { if (zpkCache.containsKey(topicId)) return }
        runCatching { readZpk(topicId) }
    }

    /** 提前把 zpk 索引建好（启动时后台调用，避免首次导航卡住） */
    fun prewarmIndex() { runCatching { zpkIndex() } }

    /** 各词书的 topicId 集合（用于跨词书过滤），解析全部 roadmap，带缓存 */
    fun bookTopicIds(): Map<WordBook, Set<Int>> {
        _bookTopicIds?.let { return it }
        synchronized(this) {
            _bookTopicIds?.let { return it }
            val m = LinkedHashMap<WordBook, Set<Int>>()
            for (b in WordBook.entries) {
                val f = DesktopPaths.roadmap(b)
                if (f.exists()) runCatching { m[b] = parseRoadmap(f).toSet() }
            }
            return m.also { _bookTopicIds = it }
        }
    }

    @Volatile private var _bookTopicIds: Map<WordBook, Set<Int>>? = null

    // ── DB 读取（JDBC） ─────────────────────────────────────────

    private fun parseRoadmap(file: File): List<Int> {
        val arr = JSONArray(file.readText())
        return (0 until arr.length()).map { arr.getJSONObject(it).getInt("topic_id") }
    }

    private fun connect(db: File): Connection =
        DriverManager.getConnection("jdbc:sqlite:${db.absolutePath}")

    private fun readMasteryStatus(book: WordBook): Map<Int, Double> {
        if (!DesktopPaths.statusDb.exists()) return emptyMap()
        val map = HashMap<Int, Double>()
        try {
            connect(DesktopPaths.statusDb).use { conn ->
                conn.createStatement().use { st ->
                    st.executeQuery("SELECT topic_id, topic_obn FROM ${book.statusTableName}").use { rs ->
                        while (rs.next()) map[rs.getInt(1)] = rs.getDouble(2)
                    }
                }
            }
        } catch (_: Exception) {}
        return map
    }

    private data class WordDetails(
        val word: String, val accent: String, val meanCn: String,
        val sentence: String, val sentenceTrans: String,
    )

    private fun readWordDetails(topicIds: Set<Int>): Map<Int, WordDetails> {
        if (!DesktopPaths.lookupDb.exists()) return emptyMap()
        val result = HashMap<Int, WordDetails>()
        connect(DesktopPaths.lookupDb).use { conn ->
            // 动态发现 dict_ 开头的表（排除 dict_bcz）
            val tables = mutableListOf<String>()
            conn.createStatement().use { st ->
                st.executeQuery("SELECT name FROM sqlite_master WHERE type='table'").use { rs ->
                    while (rs.next()) {
                        val name = rs.getString(1)
                        if (name.startsWith("dict_") && !name.startsWith("dict_bcz")) tables.add(name)
                    }
                }
            }

            for (table in tables) {
                try {
                    // PRAGMA table_info 动态检测列
                    val cols = mutableListOf<String>()
                    conn.createStatement().use { st ->
                        st.executeQuery("PRAGMA table_info($table)").use { rs ->
                            while (rs.next()) cols.add(rs.getString("name"))
                        }
                    }
                    val idCol   = cols.firstOrNull { it in listOf("topic_id", "id", "word_id") } ?: continue
                    val wordCol = cols.firstOrNull { it in listOf("word", "word_name", "en_word") } ?: continue
                    val meanCol = cols.firstOrNull { it in listOf("mean_cn", "chinese", "cn_mean", "trans", "mean") }
                    val accCol  = cols.firstOrNull { it in listOf("accent", "phonetic", "ipa") }
                    val sentCol = cols.firstOrNull { it in listOf("sentence", "example", "eg_sentence") }
                    val stCol   = cols.firstOrNull { it in listOf("sentence_trans", "eg_trans", "sentence_cn") }

                    val selCols = mutableListOf(idCol, wordCol)
                    meanCol?.let { selCols.add(it) }
                    accCol?.let  { selCols.add(it) }
                    sentCol?.let { selCols.add(it) }
                    stCol?.let   { selCols.add(it) }

                    conn.createStatement().use { st ->
                        st.executeQuery("SELECT ${selCols.joinToString(",")} FROM $table").use { rs ->
                            while (rs.next()) {
                                val id = rs.getInt(1)
                                if (id !in topicIds) continue
                                var idx = 3 // 1=id, 2=word（JDBC 列从 1 开始）
                                val mean = if (meanCol != null) rs.getString(idx++).orEmpty() else ""
                                val acc  = if (accCol  != null) rs.getString(idx++).orEmpty() else ""
                                val sent = if (sentCol != null) rs.getString(idx++).orEmpty() else ""
                                val strn = if (stCol   != null) rs.getString(idx++).orEmpty() else ""
                                result[id] = WordDetails(
                                    word = rs.getString(2).orEmpty(),
                                    accent = acc, meanCn = mean, sentence = sent, sentenceTrans = strn,
                                )
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        }
        return result
    }

    // ── zpk 索引 ────────────────────────────────────────────────

    @Volatile private var _zpkIndex: Map<Int, File>? = null

    private fun zpkIndex(): Map<Int, File> {
        _zpkIndex?.let { return it }
        synchronized(this) {
            _zpkIndex?.let { return it }
            return buildZpkIndex().also { _zpkIndex = it }
        }
    }

    private fun buildZpkIndex(): Map<Int, File> {
        val index = HashMap<Int, File>()
        val root = DesktopPaths.zpackDir
        if (root.exists()) {
            root.listFiles()?.filter { it.isDirectory }?.forEach { bookDir ->
                bookDir.listFiles()?.filter { it.isDirectory }?.forEach { subDir ->
                    subDir.listFiles { f -> f.extension == "zpk" }?.forEach { zpk ->
                        extractTopicId(zpk.name)?.let { id -> index.putIfAbsent(id, zpk) }
                    }
                }
            }
        }
        return index
    }

    /** 从文件名 zp_{topicId}_409_0_{ts}.zpk 提取 topicId */
    private fun extractTopicId(fileName: String): Int? =
        fileName.split("_").getOrNull(1)?.toIntOrNull()
}
