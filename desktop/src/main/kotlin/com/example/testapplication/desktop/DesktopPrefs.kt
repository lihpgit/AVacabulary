package com.example.testapplication.desktop

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Mac 版本地存储：单个 JSON 文件（mac_data/prefs.json）。
 * 存：斩/未斩 override、浏览进度、朗读模式、例句轮播索引。
 */
class DesktopPrefs {
    private val file = File(DesktopPaths.dataDir, "prefs.json")
    private val json: JSONObject =
        runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())

    private fun persist() {
        runCatching { file.writeText(json.toString()) }
    }

    fun getInt(key: String, def: Int): Int = json.optInt(key, def)
    fun putInt(key: String, value: Int) {
        json.put(key, value); persist()
    }

    // ── 斩/未斩 override ──
    fun loadOverrides(): MutableMap<Int, Boolean> {
        val ov = json.optJSONObject("overrides") ?: return mutableMapOf()
        val map = mutableMapOf<Int, Boolean>()
        val keys = ov.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            k.toIntOrNull()?.let { map[it] = ov.getBoolean(k) }
        }
        return map
    }

    fun saveOverrides(map: Map<Int, Boolean>) {
        val ov = JSONObject()
        map.forEach { (k, v) -> ov.put(k.toString(), v) }
        json.put("overrides", ov); persist()
    }

    // ── “不认识”集合（全局 topicId）──
    fun loadNotRecognized(): Set<Int> {
        val arr = json.optJSONArray("notRecognized") ?: return emptySet()
        val set = mutableSetOf<Int>()
        for (i in 0 until arr.length()) set.add(arr.getInt(i))
        return set
    }

    fun saveNotRecognized(set: Set<Int>) {
        val arr = JSONArray()
        set.forEach { arr.put(it) }
        json.put("notRecognized", arr); persist()
    }
}
