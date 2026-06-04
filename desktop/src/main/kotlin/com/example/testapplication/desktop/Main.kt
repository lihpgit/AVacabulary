package com.example.testapplication.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.awt.Toolkit

fun main() = application {
    val state = remember { GuessState(DesktopRepository(), DesktopPrefs(), DesktopAudio()) }
    // 所有响应式逻辑（读 zpk / 解析 / 朗读 / 存盘）跑在后台线程，
    // 绝不占用 EDT —— 否则会和 Compose 重组抢 UI 线程，导致单词/朗读延迟数秒。
    val bgScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    LaunchedEffect(Unit) { state.start(bgScope) }

    // 普通窗口 / 贴底窗口 切换
    var docked by remember { mutableStateOf(false) }

    // ── 普通窗口（带原生标题栏）──
    val normalState = rememberWindowState(width = 1100.dp, height = 760.dp)
    Window(
        onCloseRequest = ::exitApplication,
        title = "语境猜词",
        visible = !docked,
        state = normalState,
    ) {
        App(state, onDock = { docked = true })
    }

    // ── 贴底窗口（透明 / 无边框 / 置顶，常驻屏幕底部）──
    val screen = remember { Toolkit.getDefaultToolkit().screenSize }
    val barH = 150
    val dockState = rememberWindowState(
        position = WindowPosition(x = 0.dp, y = (screen.height - barH).dp),
        size = DpSize(screen.width.dp, barH.dp),
    )
    Window(
        onCloseRequest = { docked = false }, // 关掉贴底窗口 = 恢复普通窗口
        title = "语境猜词·贴底",
        visible = docked,
        undecorated = true,
        transparent = true,
        alwaysOnTop = true,
        resizable = false,
        state = dockState,
    ) {
        // 切到贴底时把窗口提到最前并抢键盘焦点，保证方向键立即可用
        LaunchedEffect(docked) {
            if (docked) { window.toFront(); runCatching { window.requestFocus() } }
        }
        DockedBar(
            state, active = docked,
            onRestore = { docked = false },
            onClose = ::exitApplication,
        )
    }
}
