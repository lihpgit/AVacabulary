package com.example.testapplication.desktop

import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 用 macOS 自带的 `afplay` 播放音频，并在每段音频**前面拼一小段静音**以彻底消除吞音。
 *
 * ## 为什么不用 JLayer
 * 百词斩部分单词发音是 AAC（甚至伪装成 .mp3 的 AAC），JLayer 只能解 MP3，遇到 AAC 直接静音。
 * afplay / afconvert 走 CoreAudio，MP3/AAC/m4a 通吃。
 *
 * ## 吞音根因与最终修复（多轮排查结论）
 * afplay 启动播放时，CoreAudio 输出设备从"停止"到"真正出声"有一段启动延迟，
 * 这段时间里送进去的开头几帧会被丢掉（**冷启动吞头**）。它是**近似固定时长**的：
 * 长单词听不出来，**短单词（如 biscuit/scary，零点几秒）开头辅音直接被吞光**。
 *
 * 试过的无效方案：翻页时 stop、加 90/220ms 延时、静音热身——都没用，
 * 因为吞音发生在"新 afplay 真正出声那一刻"，跟前面等多久无关。
 *
 * 最终修复（与机制无关、必然有效）：
 * 1. 用 `afconvert` 把音频解码成 16bit LPCM WAV（MP3/AAC 都能解）。
 * 2. 在 PCM 数据前面拼 [SILENCE_MS] 毫秒静音，重组成新 WAV。
 * 3. afplay 这个新 WAV —— 冷启动吞掉的是**前面的静音**，真实单词完整保留。
 *
 * 任一步失败则回退为直接 afplay 原始字节（至少有声，长单词不受影响）。
 */
class DesktopAudio {

    private companion object {
        /** 拼在每段音频前的静音时长（毫秒），用来吸收 CoreAudio 冷启动吞掉的开头。 */
        const val SILENCE_MS = 250
    }

    private val exec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "desktop-audio").apply { isDaemon = true }
    }
    private val gen = AtomicInteger(0)

    @Volatile private var proc: Process? = null

    fun play(bytes: ByteArray, label: String = "", onComplete: (() -> Unit)? = null) {
        val myGen = gen.incrementAndGet()
        killProc()
        exec.submit {
            if (gen.get() != myGen) return@submit
            val temps = mutableListOf<File>()
            try {
                val ext = extOf(bytes)
                val clip = File.createTempFile("vocab_clip_", ext).apply { writeBytes(bytes) }
                temps += clip

                // 解码 + 前置静音；失败则回退原始 clip。
                val padded = runCatching { buildSilencePadded(clip) }.getOrNull()
                padded?.let { temps += it }
                val target = padded ?: clip
                if (gen.get() != myGen) return@submit

                val p = ProcessBuilder("/usr/bin/afplay", target.absolutePath)
                    .redirectErrorStream(true)
                    .start()
                proc = p
                p.waitFor()
            } catch (_: Exception) {
            } finally {
                temps.forEach { runCatching { it.delete() } }
                if (gen.get() == myGen) onComplete?.invoke()
            }
        }
    }

    fun stop() {
        gen.incrementAndGet()
        killProc()
    }

    private fun killProc() {
        val p = proc ?: return
        proc = null
        try { p.destroy() } catch (_: Exception) {}
    }

    /**
     * 用 afconvert 把任意音频解成 16bit LPCM WAV，再在前面拼 [SILENCE_MS] 毫秒静音，
     * 返回新的 WAV 临时文件。任一步失败抛异常（调用方回退原始 clip）。
     */
    private fun buildSilencePadded(clip: File): File {
        val pcm = File.createTempFile("vocab_pcm_", ".wav")
        try {
            val cv = ProcessBuilder(
                "/usr/bin/afconvert", "-f", "WAVE", "-d", "LEI16",
                clip.absolutePath, pcm.absolutePath
            ).redirectErrorStream(true).start()
            val cvExit = cv.waitFor()
            require(cvExit == 0 && pcm.length() > 44) { "afconvert failed exit=$cvExit" }

            val wav = pcm.readBytes()
            val (fmt, dataOff, dataLen) = parseWav(wav)
            // fmt: audioFormat(2) channels(2) sampleRate(4) byteRate(4) blockAlign(2) bits(2)
            val sampleRate = le32(fmt, 4)
            val blockAlign = le16(fmt, 12)
            val silenceBytes = (sampleRate.toLong() * SILENCE_MS / 1000).toInt() * blockAlign
            val newDataLen = silenceBytes + dataLen

            val out = File.createTempFile("vocab_pad_", ".wav")
            out.outputStream().buffered().use { o ->
                fun s(str: String) = o.write(str.toByteArray(Charsets.US_ASCII))
                fun i32(v: Int) = o.write(byteArrayOf(
                    (v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte(),
                    ((v ushr 16) and 0xFF).toByte(), ((v ushr 24) and 0xFF).toByte()
                ))
                s("RIFF"); i32(36 + newDataLen); s("WAVE")
                s("fmt "); i32(16); o.write(fmt, 0, 16)
                s("data"); i32(newDataLen)
                o.write(ByteArray(silenceBytes))           // 前置静音
                o.write(wav, dataOff, dataLen)              // 原始 PCM 数据
            }
            return out
        } finally {
            runCatching { pcm.delete() }
        }
    }

    /** 解析 WAV，返回 (fmt body, data 起始偏移, data 长度)。遍历 chunk，容忍额外块。 */
    private fun parseWav(b: ByteArray): Triple<ByteArray, Int, Int> {
        require(b.size >= 12 && tag(b, 0) == "RIFF" && tag(b, 8) == "WAVE") { "not WAV" }
        var i = 12
        var fmt: ByteArray? = null
        var dataOff = -1; var dataLen = 0
        while (i + 8 <= b.size) {
            val id = tag(b, i)
            val sz = le32(b, i + 4)
            val body = i + 8
            when (id) {
                "fmt " -> fmt = b.copyOfRange(body, minOf(body + sz, b.size))
                "data" -> { dataOff = body; dataLen = minOf(sz, b.size - body) }
            }
            i = body + sz + (sz and 1) // chunk 按偶数对齐
        }
        require(fmt != null && fmt.size >= 16 && dataOff >= 0) { "WAV missing fmt/data" }
        return Triple(fmt, dataOff, dataLen)
    }

    private fun tag(b: ByteArray, o: Int) = String(b, o, 4, Charsets.US_ASCII)
    private fun le16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun le32(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    /** 按文件头猜扩展名（afconvert/afplay 也会内容嗅探，扩展名只是辅助） */
    private fun extOf(b: ByteArray): String {
        if (b.size >= 3 && b[0].toInt() and 0xFF == 0x49 &&
            b[1].toInt() and 0xFF == 0x44 && b[2].toInt() and 0xFF == 0x33
        ) return ".mp3" // ID3 → MP3
        if (b.size >= 2 && (b[0].toInt() and 0xFF) == 0xFF) {
            val b1 = b[1].toInt() and 0xFF
            if ((b1 and 0xF0) == 0xF0 && (b1 and 0x06) == 0) return ".aac" // ADTS AAC
            return ".mp3"
        }
        return ".mp3"
    }
}
