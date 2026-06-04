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

/**
 * zpk 内 resource.json 的单条例句数据。
 *
 * @param sentenceEn  英文例句
 * @param translate   中文翻译
 * @param audio       对应的音频文件名（与 zpk 内文件名一致）
 */
data class ZpkSentence(
    val sentenceEn: String,
    val translate: String,
    val audio: String,
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

/**
 * 解析 zpk 内 resource.json 的 sentences 数组，返回所有可用例句列表。
 * 仅保留 audio 文件在 zpk 内实际存在的条目，避免播放时找不到文件。
 * resource.json 不存在时返回空列表。
 */
fun parseZpkSentences(files: Map<String, ByteArray>): List<ZpkSentence> {
    val bytes = files["resource.json"] ?: return emptyList()
    return try {
        val j = JSONObject(String(bytes))
        val arr = j.optJSONArray("sentences") ?: return emptyList()
        val result = mutableListOf<ZpkSentence>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val audio = item.optString("audio", "")
            // 只保留 audio 文件在 zpk 内实际存在的条目
            if (audio.isBlank() || !files.containsKey(audio)) continue
            result.add(
                ZpkSentence(
                    sentenceEn = item.optString("sentenceEn", ""),
                    translate  = item.optString("translate", ""),
                    audio      = audio,
                )
            )
        }
        result
    } catch (_: Exception) {
        emptyList()
    }
}
