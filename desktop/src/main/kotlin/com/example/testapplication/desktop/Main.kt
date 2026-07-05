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
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import java.awt.Toolkit

/** 普通窗口鼠标离开后的透明度（真透明，能看到桌面） */
private const val IDLE_OPACITY = 0.15f

// 窗口模式（持久化到 prefs.json 的 window_mode，下次启动恢复）
private const val MODE_DOCKED = 0     // 贴底悬浮条
private const val MODE_NORMAL = 1     // 普通窗口
private const val MODE_MAXIMIZED = 2  // 最大化窗口

@OptIn(ExperimentalComposeUiApi::class)
fun main() = application {
    val state = remember { GuessState(DesktopRepository(), DesktopPrefs(), DesktopAudio()) }
    // 所有响应式逻辑（读 zpk / 解析 / 朗读 / 存盘）跑在后台线程，
    // 绝不占用 EDT —— 否则会和 Compose 重组抢 UI 线程，导致单词/朗读延迟数秒。
    val bgScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    LaunchedEffect(Unit) { state.start(bgScope) }

    // 上次退出时的窗口模式（贴底/正常/最大化），启动时恢复；默认贴底
    val savedMode = remember { state.prefs.getInt("window_mode", MODE_DOCKED) }
    // 普通窗口 / 贴底窗口 切换
    var docked by remember { mutableStateOf(savedMode == MODE_DOCKED) }

    // ── 普通窗口（无边框 + 真透明 → 鼠标离开可透视桌面）──
    val normalState = rememberWindowState(
        placement = if (savedMode == MODE_MAXIMIZED) WindowPlacement.Maximized else WindowPlacement.Floating,
        width = 1100.dp, height = 760.dp,
    )

    // 模式变化即持久化（贴底 or 普通/最大化的 placement），下次启动恢复
    LaunchedEffect(docked, normalState.placement) {
        val mode = when {
            docked -> MODE_DOCKED
            normalState.placement == WindowPlacement.Maximized -> MODE_MAXIMIZED
            else -> MODE_NORMAL
        }
        withContext(Dispatchers.IO) { state.prefs.putInt("window_mode", mode) }
    }
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
        // 自动朗读仅在普通窗口处于「最前/有焦点」时运行：失焦（切走/被盖/锁屏熄屏/切贴底/最小化）即停，
        // 需手动 ▶ 重启。用 OS 级窗口焦点判定，零开销。
        val windowFocused = LocalWindowInfo.current.isWindowFocused
        LaunchedEffect(windowFocused) { if (!windowFocused) state.stopAuto() }
        Box(
            Modifier.fillMaxSize()
                .onPointerEvent(PointerEventType.Enter) { hovered = true }
                .onPointerEvent(PointerEventType.Exit) { hovered = false }
        ) {
            App(
                state,
                onDock = { docked = true },
                onMinimize = { normalState.isMinimized = true },
                // 无边框窗口没有系统绿灯，手动在最大化/还原之间切换
                onMaximize = {
                    normalState.placement =
                        if (normalState.placement == WindowPlacement.Maximized) WindowPlacement.Floating
                        else WindowPlacement.Maximized
                },
                onClose = ::exitApplication,
                awtWindow = window,
            )
        }
    }

    // ── 贴底窗口（透明 / 无边框，常驻屏幕底部）──
    // 不再 alwaysOnTop：允许其他窗口覆盖，摸鱼时更不显眼；鼠标移入可见区域才抢焦点。
    val screen = remember { Toolkit.getDefaultToolkit().screenSize }
    val barH = 120
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
        resizable = false,
        state = dockState,
    ) {
        // 切到贴底时把窗口提到最前并抢键盘焦点，保证方向键立即可用
        LaunchedEffect(docked) {
            if (docked) { window.toFront(); runCatching { window.requestFocus() } }
        }
        DockedBar(
            state, active = docked, awtWindow = window,
            onRestore = { docked = false },
            onClose = ::exitApplication,
        )
    }
}
