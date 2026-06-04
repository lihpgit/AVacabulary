package com.example.testapplication.desktop

import org.json.JSONObject

/**
 * 与 Android `WordRepository.exportProgress/importProgress` 对齐的同步格式。
 * 核心字段 `overrides`（topicId 字符串 → 是否已斩），两端互通：
 * - Mac 导出 {"overrides":{...}} → Android「从文件导入」可读
 * - Android 导出（含 overrides + readTopicIds_*）→ Mac 只取 overrides
 *
 * Mac 没有"已读标记"概念，导出时不含，导入时忽略，不影响 Android 的已读标记。
 */
object SyncJson {

    fun export(overrides: Map<Int, Boolean>): String {
        val root = JSONObject()
        val ov = JSONObject()
        overrides.forEach { (k, v) -> ov.put(k.toString(), v) }
        root.put("overrides", ov)
        return root.toString()
    }

    fun importOverrides(jsonStr: String): Map<Int, Boolean> {
        // 去掉开头的 UTF-8 BOM(0xFEFF) 和空白，避免 "A JSONObject text must begin with '{'"
        val cleaned = jsonStr.dropWhile { it.code == 0xFEFF || it.isWhitespace() }
        val root = JSONObject(cleaned)
        val ov = root.optJSONObject("overrides") ?: return emptyMap()
        val map = mutableMapOf<Int, Boolean>()
        val keys = ov.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            k.toIntOrNull()?.let { map[it] = ov.getBoolean(k) }
        }
        return map
    }
}
