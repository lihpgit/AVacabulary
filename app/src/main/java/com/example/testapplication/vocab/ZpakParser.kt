package com.example.testapplication.vocab

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 百词斩 ZPAK0300 资源包解析器。
 * 每个 zpk 文件包含 meta.json、mp3 音频、图片等，无加密无压缩。
 */
object ZpakParser {

    private const val MAX_ENTRIES = 10_000
    private const val MAX_ENTRY_SIZE = 50 * 1024 * 1024 // 50MB

    fun parse(data: ByteArray): Map<String, ByteArray> {
        if (data.size < 128) return emptyMap()

        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

        // 验证 magic
        val magic = String(data, 0, 8, Charsets.US_ASCII)
        if (!magic.startsWith("ZPAK")) return emptyMap()

        val numEntries  = buf.getInt(0x0C)
        val indexOffset = buf.getLong(0x10)
        val nameOffset  = buf.getLong(0x18)

        if (numEntries <= 0 || numEntries > MAX_ENTRIES) return emptyMap()
        if (indexOffset <= 0 || nameOffset <= 0) return emptyMap()
        if (indexOffset >= data.size || nameOffset >= data.size) return emptyMap()

        // 读索引表（每条 48 字节）
        data class Entry(val offset: Long, val size: Int)

        val entries = mutableListOf<Entry>()
        val indexStart = indexOffset.toInt()
        for (i in 0 until numEntries) {
            val base = indexStart + i.toLong() * 48
            if (base < 0 || base + 20 > data.size) break
            val entrySize = buf.getInt((base + 16).toInt())
            if (entrySize < 0 || entrySize > MAX_ENTRY_SIZE) continue
            entries.add(
                Entry(
                    offset = buf.getLong(base.toInt()),
                    size   = entrySize
                )
            )
        }

        // 读文件名表（\n 分隔，\0 终止）
        val nameStart = nameOffset.toInt()
        var nameEnd = nameStart
        while (nameEnd < data.size && data[nameEnd] != 0.toByte()) nameEnd++

        val names = String(data, nameStart, nameEnd - nameStart, Charsets.UTF_8)
            .split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        // 按索引顺序映射 文件名 → 内容
        val result = mutableMapOf<String, ByteArray>()
        val count = minOf(entries.size, names.size)
        for (i in 0 until count) {
            val entry = entries[i]
            val name  = names[i]
            val start = entry.offset.toInt()
            val end   = start.toLong() + entry.size
            if (start >= 0 && end <= data.size && entry.size > 0) {
                result[name] = data.copyOfRange(start, end.toInt())
            }
        }
        return result
    }
}
