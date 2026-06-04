package com.example.testapplication.desktop

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
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

/** 普通窗口鼠标离开后的透明度（真透明，能看到桌面） */
private const val IDLE_OPACITY = 0.15f

@OptIn(ExperimentalComposeUiApi::class)
fun main() = application {
    val state = remember { GuessState(DesktopRepository(), DesktopPrefs(), DesktopAudio()) }
    // 所有响应式逻辑（读 zpk / 解析 / 朗读 / 存盘）跑在后台线程，
    // 绝不占用 EDT —— 否则会和 Compose 重组抢 UI 线程，导致单词/朗读延迟数秒。
    val bgScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    LaunchedEffect(Unit) { state.start(bgScope) }

    // 普通窗口 / 贴底窗口 切换
    var docked by remember { mutableStateOf(false) }

    // ── 普通窗口（无边框 + 真透明 → 鼠标离开可透视桌面）──
    val normalState = rememberWindowState(width = 1100.dp, height = 760.dp)
    Window(
        onCloseRequest = ::exitApplication,
        title = "语境猜词",
        visible = !docked,
        undecorated = true,
        transparent = true,
        state = normalState,
    ) {
        var hovered by remember { mutableStateOf(true) }
        val target = if (!state.autoTransparent || hovered) 1f else IDLE_OPACITY
        LaunchedEffect(target) {
            animate(window.opacity, target, animationSpec = tween(250)) { v, _ -> window.opacity = v }
        }
        Box(
            Modifier.fillMaxSize()
                .onPointerEvent(PointerEventType.Enter) { hovered = true }
                .onPointerEvent(PointerEventType.Exit) { hovered = false }
        ) {
            App(state, onDock = { docked = true }, onClose = ::exitApplication, awtWindow = window)
        }
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
