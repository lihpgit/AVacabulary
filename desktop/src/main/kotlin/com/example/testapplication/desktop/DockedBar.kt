package com.example.testapplication.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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

/**
 * 贴底悬浮条：透明背景、常驻屏幕底部。
 * - 鼠标进入 → 显示当前单词/音标/例句（点击或方向键 ⬆️/空格切换翻译）。
 * - 鼠标移开 → 全部隐藏（仅留一条极淡的提示线，便于找到）。
 * - 键盘：← → 翻页，⬆️/空格 切换翻译，⬇️ 读例句，P 读单词。
 * - 右侧：恢复正常窗口、关闭。
 *
 * @param active 该窗口当前是否处于贴底显示状态（用于在切到贴底时抢键盘焦点）。
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DockedBar(state: GuessState, active: Boolean, onRestore: () -> Unit, onClose: () -> Unit) {
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
                        else -> false
                    }
                }
                .onPointerEvent(PointerEventType.Enter) { hovered = true }
                .onPointerEvent(PointerEventType.Exit) { hovered = false },
            contentAlignment = Alignment.BottomCenter,
        ) {
            if (!hovered) {
                // 未悬停：极淡提示线，便于定位（其余完全透明）
                Box(
                    Modifier.fillMaxWidth().height(4.dp).align(Alignment.BottomCenter)
                        .background(Color.White.copy(alpha = 0.14f))
                )
            } else {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Black.copy(alpha = 0.82f),
                    shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp),
                    tonalElevation = 0.dp,
                ) {
                    Row(
                        Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // ← 上一个
                        IconTextButton("◀", enabled = state.currentIndex > 0) { state.goPrev() }

                        // 内容（点击切换翻译）：单词+音标+例句 同一行，太长才换行
                        Column(
                            Modifier.weight(1f).fillMaxHeight()
                                .padding(horizontal = 16.dp)
                                .verticalScroll(rememberScrollState())
                                .clickable { state.toggleReveal() },
                            verticalArrangement = Arrangement.Center,
                        ) {
                            val primary = MaterialTheme.colorScheme.primary
                            if (currentWord == null) {
                                Text("没有单词", color = Color.White)
                            } else {
                                val line = buildAnnotatedString {
                                    withStyle(SpanStyle(fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)) {
                                        append(currentWord.word)
                                    }
                                    if (accent.isNotBlank()) {
                                        append("  ")
                                        withStyle(SpanStyle(fontSize = 17.sp, color = Color.White.copy(alpha = 0.6f))) {
                                            append(accent)
                                        }
                                    }
                                    if (sentence.isNotBlank()) {
                                        append("    ")
                                        withStyle(SpanStyle(fontSize = 22.sp, color = Color.White)) {
                                            append(highlight(sentence, currentWord.word, primary))
                                        }
                                    }
                                }
                                Text(line, lineHeight = 36.sp)

                                if (state.revealed) {
                                    val trans = buildAnnotatedString {
                                        if (meanCn.isNotBlank()) {
                                            withStyle(SpanStyle(fontSize = 23.sp, fontWeight = FontWeight.Medium, color = primary)) {
                                                append(meanCn)
                                            }
                                        }
                                        if (sentenceTrans.isNotBlank()) {
                                            if (meanCn.isNotBlank()) append("    ")
                                            withStyle(SpanStyle(fontSize = 18.sp, color = Color.White.copy(alpha = 0.8f))) {
                                                append(sentenceTrans)
                                            }
                                        }
                                    }
                                    if (trans.isNotEmpty()) {
                                        Spacer(Modifier.height(4.dp))
                                        Text(trans, lineHeight = 26.sp)
                                    }
                                }
                            }
                        }

                        // 下一个 ▶
                        IconTextButton("▶", enabled = true) { state.goNext() }
                        Spacer(Modifier.width(10.dp))

                        // 斩 / 翻译 / 恢复 / 关闭
                        val eff = state.effectiveMastered
                        TextButton(onClick = { state.toggleMastered() }) {
                            Text(if (eff) "已斩" else "斩",
                                color = if (eff) Color.White.copy(alpha = 0.6f) else MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.Bold)
                        }
                        TextButton(onClick = { state.toggleReveal() }) {
                            Text(if (state.revealed) "隐藏" else "翻译", color = Color.White)
                        }
                        TextButton(onClick = onRestore) { Text("恢复窗口", color = Color.White) }
                        TextButton(onClick = onClose) { Text("关闭", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }
}

@Composable
private fun IconTextButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled) {
        Text(label, fontSize = 22.sp,
            color = if (enabled) Color.White else Color.White.copy(alpha = 0.3f))
    }
}
