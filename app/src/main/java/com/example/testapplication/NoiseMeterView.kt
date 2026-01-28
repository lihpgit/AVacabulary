// NoiseMeterView.kt
package com.example.testapplication

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.max

data class NoiseLevel(
    val range: IntRange,
    val label: String,
    val color: Color
)

private val NOISE_LEVELS = listOf(
    NoiseLevel(0..20, "极静 (呼吸声)", Color(0xFFE3F2FD)),
    NoiseLevel(20..40, "安静 (图书馆)", Color(0xFFBBDEFB)),
    NoiseLevel(40..60, "一般 (交谈)", Color(0xFF90CAF9)),
    NoiseLevel(60..80, "吵闹 (街道)", Color(0xFF64B5F6)),
    NoiseLevel(80..100, "嘈杂 (施工)", Color(0xFF42A5F5)),
    NoiseLevel(100..140, "痛苦 (鸣笛)", Color(0xFF1E88E5))
)

@Composable
fun NoiseMeterView() {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    var isRunning by remember { mutableStateOf(false) }
    // 模拟 SPL: 将 dBFS (-160 ~ 0) 映射到近似 SPL (0 ~ 100+)。
    // 假设手机 mic 的 0 dBFS 约为 110 dB SPL (经验值，仅供演示)
    // dB_SPL = dBFS + OFFSET
    val offset = 110.0
    
    var currentDb by remember { mutableStateOf(0.0) }
    // 历史数据，用于画柱状图
    val history = remember { mutableStateListOf<Double>() }
    val maxHistorySize = 50

    var meterJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (!granted) {
            isRunning = false
            meterJob?.cancel()
            meterJob = null
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            meterJob?.cancel()
        }
    }

    // 自动开始
    LaunchedEffect(hasPermission) {
        if (hasPermission && !isRunning) {
            isRunning = true
            meterJob = scope.launch {
                startNoiseMeter { dbfs ->
                    // 简单的平滑处理
                    val spl = max(0.0, dbfs + offset)
                    currentDb = spl
                    history.add(spl)
                    if (history.size > maxHistorySize) {
                        history.removeAt(0)
                    }
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // 顶部：实时数值
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.3f),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "%.1f".format(currentDb),
                    style = MaterialTheme.typography.displayLarge.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                )
                Text(
                    text = "dB SPL (近似值)",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.Gray
                )
                if (!hasPermission) {
                    Button(onClick = { launcher.launch(Manifest.permission.RECORD_AUDIO) }) {
                        Text("授予麦克风权限")
                    }
                }
            }
        }

        // 底部区域
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.7f)
        ) {
            // 左下：场景条
            Column(
                modifier = Modifier
                    .weight(0.35f)
                    .fillMaxHeight()
                    .padding(end = 8.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // 倒序遍历，让大分贝在上面
                NOISE_LEVELS.reversed().forEach { level ->
                    val isActive = currentDb >= level.range.first && currentDb < level.range.last
                    // 如果超过最大值，最上面那个也亮
                    val isHighActive = level == NOISE_LEVELS.last() && currentDb >= level.range.last
                    
                    val activeColor = if (isActive || isHighActive) level.color else Color.LightGray.copy(alpha = 0.3f)
                    val scale by animateFloatAsState(if (isActive || isHighActive) 1.05f else 1.0f, label = "scale")

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(vertical = 2.dp)
                            .background(activeColor, RoundedCornerShape(4.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${level.range.first}-${level.range.last} dB\n${level.label}",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                            color = if (isActive || isHighActive) Color.Black else Color.Gray,
                            modifier = Modifier.padding(4.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }

            // 右下：柱状图
            Box(
                modifier = Modifier
                    .weight(0.65f)
                    .fillMaxHeight()
                    .background(Color.Black.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val barWidth = size.width / maxHistorySize
                    val maxDb = 120f // Y轴最大值

                    history.forEachIndexed { index, dbValue ->
                        val barHeight = (dbValue.toFloat() / maxDb) * size.height
                        // 限制高度不超过画布
                        val actualHeight = barHeight.coerceIn(0f, size.height)
                        
                        // 颜色根据分贝值变化
                        val color = NOISE_LEVELS.find { dbValue in it.range.first.toDouble()..it.range.last.toDouble() }?.color ?: Color.Red

                        drawRect(
                            color = color,
                            topLeft = Offset(
                                x = index * barWidth,
                                y = size.height - actualHeight
                            ),
                            size = Size(barWidth - 2f, actualHeight)
                        )
                    }
                }
            }
        }
    }
}
