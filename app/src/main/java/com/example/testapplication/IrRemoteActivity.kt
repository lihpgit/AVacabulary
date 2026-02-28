package com.example.testapplication

import android.content.Context
import android.hardware.ConsumerIrManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * 手机红外功能演示
 *
 * 作用：通过红外发射器 (IR Blaster) 发射红外信号，可充当万能遥控器，
 * 控制电视、空调、机顶盒、投影仪等家电。
 *
 * 注意：并非所有手机都有红外硬件，常见支持机型：小米、华为、荣耀部分型号。
 */
class IrRemoteActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            IrRemoteView()
        }
    }
}

@Composable
fun IrRemoteView() {
    val context = LocalContext.current
    val irManager = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            context.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager
        } else null
    }

    var statusText by remember { mutableStateOf("检测中...") }
    var freqText by remember { mutableStateOf("") }
    var lastSendResult by remember { mutableStateOf("") }

    LaunchedEffect(irManager) {
        if (irManager == null) {
            statusText = "系统不支持 (需 Android 4.4+)"
        } else if (!irManager.hasIrEmitter()) {
            statusText = "❌ 当前设备无红外发射器"
            freqText = "部分手机才有此硬件，如小米、华为部分型号"
        } else {
            statusText = "✅ 设备支持红外遥控"
            val freqs = irManager.carrierFrequencies
            freqText = if (freqs.isNullOrEmpty()) {
                "无法获取频率范围"
            } else {
                freqs.joinToString("\n") { "  ${it.minFrequency / 1000} ~ ${it.maxFrequency / 1000} kHz" }
            }
        }
    }

    fun sendTestIr() {
        if (irManager == null || !irManager.hasIrEmitter()) {
            Toast.makeText(context, "设备不支持红外", Toast.LENGTH_SHORT).show()
            lastSendResult = "设备不支持"
            return
        }

        try {
            // NEC 协议常用载波 38kHz
            val carrierFreq = 38000
            // 通用 NEC 格式的「电源」码示例 (部分电视/机顶盒可识别)
            // 格式：起始 9ms 高 + 4.5ms 低，随后数据位 (560μs 高 + 560/1680μs 低)
            val pattern = intArrayOf(
                9000, 4500,  // NEC 起始位
                560, 560, 560, 1680, 560, 560, 560, 560, 560, 1680, 560, 560, 560, 1680, 560, 560, 560, 560, 560, 560, 560, 1680, 560, 1680, 560, 560, 560, 560, 560, 1680, 560, 560, 560, 560, 560, 560, 560, 560, 560, 1680, 560, 1680, 560, 1680, 560, 1680, 560, 1680, 560, 1680, 560, 1680, 560, 40000
            )
            irManager.transmit(carrierFreq, pattern)
            lastSendResult = "已发送红外信号，请对准电视/空调等设备"
            Toast.makeText(context, "已发送", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            lastSendResult = "发送失败: ${e.message}"
            Toast.makeText(context, "发送失败", Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("红外遥控演示", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(24.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("硬件状态", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text(statusText, style = MaterialTheme.typography.bodyLarge)
            }
        }

        if (freqText.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("支持的载波频率 (kHz)", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(freqText, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (irManager != null && irManager.hasIrEmitter()) {
            Button(onClick = { sendTestIr() }) {
                Text("发送测试信号 (通用电源码)")
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "对准电视/空调等设备顶部红外接收口",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (lastSendResult.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = lastSendResult,
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("红外功能说明", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "• 手机红外发射器可替代遥控器，控制支持红外的家电\n" +
                            "• 不同品牌/型号设备使用不同协议(NEC/RC5/索尼等)和编码\n" +
                            "• 本演示发送通用 NEC 电源码，部分设备可能无反应\n" +
                            "• 实际应用需按设备协议生成对应遥控码",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
