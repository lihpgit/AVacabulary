package com.example.testapplication.vocab

import org.json.JSONObject

/** zpk 内 meta.json 的词典字段（与闪卡展示、音频路径一致） */
data class ZpkMeta(
    val accent: String,
    val meanCn: String,
    val meanEn: String,
    val sentence: String,
    val sentenceTrans: String,
    val wordAudio: String,
    val sentenceAudio: String,
)

fun parseZpkMeta(files: Map<String, ByteArray>): ZpkMeta? {
    val bytes = files["meta.json"] ?: return null
    return try {
        val j = JSONObject(String(bytes))
        ZpkMeta(
            accent = j.optString("accent", ""),
            meanCn = j.optString("mean_cn", ""),
            meanEn = j.optString("mean_en", ""),
            sentence = j.optString("sentence", ""),
            sentenceTrans = j.optString("sentence_trans", ""),
            wordAudio = j.optString("word_audio", ""),
            sentenceAudio = j.optString("sentence_audio", ""),
        )
    } catch (_: Exception) {
        null
    }
}

/**
 * 与闪卡单词朗读相同的 mp3 选取逻辑：`meta.word_audio`，否则 `uk_*.mp3`。
 */
fun wordAudioBytesFromZpk(files: Map<String, ByteArray>): ByteArray? {
    val meta = parseZpkMeta(files)
    val key = meta?.wordAudio?.ifBlank { null }
        ?: files.keys.find { it.startsWith("uk_") && it.endsWith(".mp3") }
    return key?.let { files[it] }
}
