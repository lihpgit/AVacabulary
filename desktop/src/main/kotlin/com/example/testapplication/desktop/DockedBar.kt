package com.example.testapplication.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.awt.MouseInfo
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import kotlinx.coroutines.delay

// ── 摸鱼版配色：半透明黑底 + 低对比白字，尽量不像「一个 App」──
private val DOCK_BG = Color.Black.copy(alpha = 0.4f)   // 背景：0.4 透明度黑
private val DOCK_INK = Color.White.copy(alpha = 0.6f)  // 主文字：0.6 透明度白
private val DOCK_DIM = Color.White.copy(alpha = 0.4f)  // 次要文字：更淡的白（音标/译文）

/**
 * 贴底悬浮条（摸鱼形态）：完全透明背景、常驻屏幕底部、不置顶（可被其他窗口覆盖）。
 * - 鼠标进入可见区域 → 抢键盘焦点并显示当前单词/音标/例句（浅灰小字、无高亮）。
 * - 鼠标移开 → 全部隐藏（仅留一条几乎不可见的提示线，便于定位）。
 * - 键盘：← → 翻页，⬆️/空格 切换翻译，⬇️ 读例句，P 读单词，Esc 恢复普通窗口。
 * - 仅保留一个低调的「斩」键，其余操作全走键盘。
 *
 * @param active 该窗口当前是否处于贴底显示状态（用于在切到贴底时抢键盘焦点）。
 * @param awtWindow 底层 AWT 窗口；鼠标移入时抢窗口级焦点，保证不置顶时键盘仍可用。
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DockedBar(
    state: GuessState,
    active: Boolean,
    awtWindow: java.awt.Window? = null,
    onRestore: () -> Unit,
    onClose: () -> Unit,
) {
    // 无标题栏 → 通过 AWT 实现整条底栏可拖动（按下不动=点击翻译，按下拖动=移动窗口）
    DisposableEffect(awtWindow) {
        if (awtWindow == null) return@DisposableEffect onDispose {}
        var dragStart = Point()
        val pressListener = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) { dragStart = e.point }
        }
        val dragListener = object : MouseMotionAdapter() {
            override fun mouseDragged(e: MouseEvent) {
                val loc = awtWindow.location
                awtWindow.setLocation(loc.x + e.x - dragStart.x, loc.y + e.y - dragStart.y)
            }
        }
        awtWindow.addMouseListener(pressListener)
        awtWindow.addMouseMotionListener(dragListener)
        onDispose {
            awtWindow.removeMouseListener(pressListener)
            awtWindow.removeMouseMotionListener(dragListener)
        }
    }
    MaterialTheme(colorScheme = darkColorScheme()) {
        var hovered by remember { mutableStateOf(false) }

        val currentWord = state.currentWord
        val zs = state.sentences.getOrNull(state.sentenceIdx)
        val accent = currentWord?.accent?.ifBlank { state.zpkMeta?.accent ?: "" } ?: ""
        val sentence = zs?.sentenceEn?.ifBlank { null }
            ?: currentWord?.sentence?.ifBlank { state.zpkMeta?.sentence ?: "" } ?: ""
        val meanCn = currentWord?.meanCn?.ifBlank { state.zpkMeta?.meanCn ?: "" } ?: ""
        val sentenceTrans = zs?.translate?.ifBlank { null }
            ?: currentWord?.sentenceTrans?.ifBlank { state.zpkMeta?.sentenceTrans ?: "" } ?: ""

        val focus = remember { FocusRequester() }
        // 切到贴底 / 鼠标进入时抢键盘焦点
        LaunchedEffect(active) { if (active) runCatching { focus.requestFocus() } }
        LaunchedEffect(hovered) { if (hovered) runCatching { focus.requestFocus() } }

        // 兜底：被其他窗口覆盖时，鼠标移入被遮挡区会让 Compose 漏掉 Exit，hovered 卡在 true。
        // 显示期间轮询鼠标真实屏幕坐标，离开本窗口矩形就强制收起。
        LaunchedEffect(hovered, awtWindow) {
            if (!hovered || awtWindow == null) return@LaunchedEffect
            while (true) {
                delay(150)
                val p = MouseInfo.getPointerInfo()?.location ?: continue
                val b = runCatching { awtWindow.bounds }.getOrNull() ?: continue
                if (!b.contains(p)) { hovered = false; break }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(focus)
                .focusable()
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.DirectionLeft -> { state.goPrev(); true }
                        Key.DirectionRight -> { state.goNext(); true }
                        Key.DirectionUp, Key.Spacebar -> { state.toggleReveal(); true }
                        Key.P -> { state.playWord(); true }
                        Key.DirectionDown -> { state.playSentence(); true }
                        Key.Escape -> { onRestore(); true } // Esc 恢复普通窗口
                        else -> false
                    }
                }
                .onPointerEvent(PointerEventType.Enter) {
                    hovered = true
                    // 不置顶时鼠标移入要主动抢窗口焦点，否则方向键收不到
                    runCatching { awtWindow?.requestFocus() }
                }
                .onPointerEvent(PointerEventType.Exit) { hovered = false },
            contentAlignment = Alignment.BottomCenter,
        ) {
            if (!hovered) {
                // 未悬停：几乎不可见的提示线，便于定位（其余完全透明）
                Box(
                    Modifier.fillMaxWidth().height(2.dp).align(Alignment.BottomCenter)
                        .background(Color.White.copy(alpha = 0.05f))
                )
            } else {
                // 悬停：半透明黑底 + 低对比白字，高度随内容（1~2 行）紧贴底部，不留多余间距
                Row(
                    Modifier.fillMaxWidth().wrapContentHeight().background(DOCK_BG)
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        Modifier.weight(1f).clickable { state.toggleReveal() },
                        verticalArrangement = Arrangement.Center,
                    ) {
                        // 朗读被拦截（未连蓝牙）时的提示
                        state.audioHint?.let { hint ->
                            Text(hint, color = Color(0xFFFFB74D), fontSize = 22.sp)
                            Spacer(Modifier.height(2.dp))
                        }
                        if (currentWord == null) {
                            Text("没有单词", color = DOCK_DIM, fontSize = 40.sp)
                        } else {
                            // 单词 + 音标 + 例句 同一行，统一浅白、无高亮
                            val line = buildAnnotatedString {
                                withStyle(SpanStyle(fontSize = 32.sp, color = DOCK_INK)) {
                                    append(currentWord.word)
                                }
                                if (accent.isNotBlank()) {
                                    append("  ")
                                    withStyle(SpanStyle(fontSize = 21.sp, color = DOCK_DIM)) {
                                        append(accent)
                                    }
                                }
                                if (sentence.isNotBlank()) {
                                    append("    ")
                                    withStyle(SpanStyle(fontSize = 22.sp, color = DOCK_INK)) {
                                        append(sentence)
                                    }
                                }
                            }
                            Text(line, lineHeight = 44.sp)

                            if (state.revealed) {
                                val trans = buildAnnotatedString {
                                    if (meanCn.isNotBlank()) {
                                        withStyle(SpanStyle(fontSize = 25.sp, color = DOCK_INK)) {
                                            append(meanCn)
                                        }
                                    }
                                    if (sentenceTrans.isNotBlank()) {
                                        if (meanCn.isNotBlank()) append("    ")
                                        withStyle(SpanStyle(fontSize = 22.sp, color = DOCK_DIM)) {
                                            append(sentenceTrans)
                                        }
                                    }
                                }
                                if (trans.isNotEmpty()) {
                                    Spacer(Modifier.height(2.dp))
                                    Text(trans, lineHeight = 32.sp)
                                }
                            }
                        }
                    }

                    // 「斩」键
                    val eff = state.effectiveMastered
                    TextButton(onClick = { state.toggleMastered() }) {
                        Text(if (eff) "已斩" else "斩",
                            fontSize = 22.sp,
                            color = if (eff) DOCK_DIM.copy(alpha = 0.6f) else DOCK_INK)
                    }
                    // 恢复普通窗口（双窗叠加图标）
                    TextButton(onClick = onRestore) {
                        Text("❐", fontSize = 24.sp, color = DOCK_INK)
                    }
                }
            }
        }
    }
}
