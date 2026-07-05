package com.example.testapplication.desktop

import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 检测 macOS 当前**默认音频输出设备**是否为蓝牙——用于「仅蓝牙耳机才朗读」（防外放）。
 *
 * 走 `system_profiler SPAudioDataType -json`（亚秒级），找出 `coreaudio_default_audio_output_device == "spaudio_yes"`
 * 的设备，看其 `coreaudio_device_transport` 是否含 `bluetooth`（蓝牙耳机/音箱）。
 *
 * 注意：本检测会 fork 子进程、阻塞约 0.1~0.3s，**不可在 EDT/UI 线程直接调用**，
 * 调用方（[GuessState]）在后台协程里轮询并缓存结果。任何异常（非 macOS、命令缺失、JSON 异常）
 * 一律按 `false`（即默认不放行）处理，由上层开关决定是否真正拦截。
 */
object MacAudioOutput {

    /** 当前默认输出设备是否为蓝牙；检测失败一律返回 false。 */
    fun isBluetoothOutputActive(): Boolean = runCatching {
        val p = ProcessBuilder("/usr/sbin/system_profiler", "SPAudioDataType", "-json")
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(5, TimeUnit.SECONDS)) { p.destroy(); return@runCatching false }

        val items = JSONObject(out)
            .getJSONArray("SPAudioDataType")
            .getJSONObject(0)
            .getJSONArray("_items")
        for (i in 0 until items.length()) {
            val d = items.getJSONObject(i)
            if (d.optString("coreaudio_default_audio_output_device") == "spaudio_yes") {
                return@runCatching d.optString("coreaudio_device_transport")
                    .contains("bluetooth", ignoreCase = true)
            }
        }
        false
    }.getOrDefault(false)
}
