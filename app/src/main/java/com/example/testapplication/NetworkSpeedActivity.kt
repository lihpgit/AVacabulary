package com.example.testapplication

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.max

class NetworkSpeedActivity : ComponentActivity() {

    private val boundServiceState = mutableStateOf<NetworkSpeedService?>(null)
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val localBinder = service as? NetworkSpeedService.LocalBinder ?: return
            boundServiceState.value = localBinder.getService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            boundServiceState.value = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NetworkSpeedView(boundServiceState.value)
        }
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, NetworkSpeedService::class.java)
        isBound = bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        boundServiceState.value?.stopSelfIfIdle()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
            boundServiceState.value = null
        }
    }
}

@Composable
fun NetworkSpeedView(service: NetworkSpeedService?) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var downloadUrl by rememberSaveable { mutableStateOf(NetworkSpeedService.DEFAULT_DOWNLOAD_URL) }
    var uploadUrl by rememberSaveable { mutableStateOf(NetworkSpeedService.DEFAULT_UPLOAD_URL) }
    var probeUrl by rememberSaveable { mutableStateOf(NetworkSpeedService.DEFAULT_PROBE_URL) }
    val history = remember { mutableStateListOf<SpeedPoint>() }

    val snapshot = if (service != null) {
        val state by service.stats.collectAsState()
        state
    } else {
        NetworkSpeedSnapshot()
    }

    LaunchedEffect(snapshot.timestampMs, service) {
        if (service == null) {
            history.clear()
            return@LaunchedEffect
        }
        history.add(SpeedPoint(snapshot.downloadSpeedBps, snapshot.uploadSpeedBps))
        if (history.size > 30) history.removeAt(0)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("网络流量测速 Demo", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(12.dp))

        Text("服务状态: ${if (service == null) "未连接" else "已连接"}")
        Spacer(modifier = Modifier.height(8.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("实时速度", style = MaterialTheme.typography.titleMedium)
                Text("下载速度: ${formatSpeed(snapshot.downloadSpeedBps)}")
                Text("上传速度: ${formatSpeed(snapshot.uploadSpeedBps)}")
                Text("累计下载: ${formatBytes(snapshot.totalDownloadedBytes)}")
                Text("累计上传: ${formatBytes(snapshot.totalUploadedBytes)}")
                Text("UID Rx: ${formatBytes(snapshot.uidRxBytes)}")
                Text("UID Tx: ${formatBytes(snapshot.uidTxBytes)}")
                if (!snapshot.lastError.isNullOrBlank()) {
                    Text("最近错误: ${snapshot.lastError}")
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("网络稳定性", style = MaterialTheme.typography.titleMedium)
                Text("探测状态: ${if (snapshot.isProbeRunning) "运行中" else "未运行"}")
                Text("稳定性判断: ${networkQualityLabel(snapshot)}")
                Text("探测总数: ${snapshot.probeTotalCount}")
                Text("丢包数: ${snapshot.probeLossCount}")
                Text("丢包率: ${String.format("%.2f", snapshot.probeLossRate)}%")
                Text("平均 RTT: ${if (snapshot.probeAvgRttMs > 0) String.format("%.1f ms", snapshot.probeAvgRttMs) else "--"}")
                Text("抖动 Jitter: ${if (snapshot.probeJitterMs > 0) String.format("%.1f ms", snapshot.probeJitterMs) else "--"}")
                Text("最近 RTT: ${if (snapshot.probeLastRttMs >= 0) "${snapshot.probeLastRttMs} ms" else "--"}")
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("最近30秒速度曲线", style = MaterialTheme.typography.titleMedium)
                SpeedChart(history = history)
                Text(
                    text = "蓝色=下载，红色=上传，纵轴按当前窗口最大值自适应",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        OutlinedTextField(
            value = downloadUrl,
            onValueChange = { downloadUrl = it },
            label = { Text("下载地址") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    context.startService(Intent(context, NetworkSpeedService::class.java))
                    service?.startDownloadTest(downloadUrl)
                },
                enabled = service != null && !snapshot.isDownloadRunning,
                modifier = Modifier.weight(1f)
            ) {
                Text("开始下载")
            }
            Button(
                onClick = { service?.stopDownloadTest() },
                enabled = service != null && snapshot.isDownloadRunning,
                modifier = Modifier.weight(1f)
            ) {
                Text("停止下载")
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        OutlinedTextField(
            value = uploadUrl,
            onValueChange = { uploadUrl = it },
            label = { Text("上传地址") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    context.startService(Intent(context, NetworkSpeedService::class.java))
                    service?.startUploadTest(uploadUrl)
                },
                enabled = service != null && !snapshot.isUploadRunning,
                modifier = Modifier.weight(1f)
            ) {
                Text("开始上传")
            }
            Button(
                onClick = { service?.stopUploadTest() },
                enabled = service != null && snapshot.isUploadRunning,
                modifier = Modifier.weight(1f)
            ) {
                Text("停止上传")
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        OutlinedTextField(
            value = probeUrl,
            onValueChange = { probeUrl = it },
            label = { Text("探测地址(丢包/RTT)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    context.startService(Intent(context, NetworkSpeedService::class.java))
                    service?.startProbeTest(probeUrl)
                },
                enabled = service != null && !snapshot.isProbeRunning,
                modifier = Modifier.weight(1f)
            ) {
                Text("开始探测")
            }
            Button(
                onClick = { service?.stopProbeTest() },
                enabled = service != null && snapshot.isProbeRunning,
                modifier = Modifier.weight(1f)
            ) {
                Text("停止探测")
            }
            Button(
                onClick = { service?.resetProbeStats() },
                enabled = service != null,
                modifier = Modifier.weight(1f)
            ) {
                Text("重置统计")
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Button(
            onClick = {
                service?.stopAllTests()
                context.startService(
                    Intent(context, NetworkSpeedService::class.java)
                        .setAction(NetworkSpeedService.ACTION_STOP_SERVICE)
                )
            },
            enabled = service != null,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("全部停止")
        }
    }
}

@Composable
private fun SpeedChart(history: List<SpeedPoint>) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(2.4f / 1f)
    ) {
        if (history.isEmpty()) return@Canvas
        val maxSpeed = max(
            1f,
            history.maxOf { max(it.downloadBps, it.uploadBps).toFloat() }
        )
        val stepX = if (history.size == 1) 0f else size.width / (history.size - 1).toFloat()

        val downPath = Path()
        val upPath = Path()

        history.forEachIndexed { index, point ->
            val x = index * stepX
            val downY = size.height - (point.downloadBps / maxSpeed) * size.height
            val upY = size.height - (point.uploadBps / maxSpeed) * size.height

            if (index == 0) {
                downPath.moveTo(x, downY)
                upPath.moveTo(x, upY)
            } else {
                downPath.lineTo(x, downY)
                upPath.lineTo(x, upY)
            }
        }

        val gridColor = Color(0xFFB0BEC5)
        repeat(4) { i ->
            val y = size.height * i / 3f
            drawLine(
                color = gridColor.copy(alpha = 0.35f),
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1f
            )
        }

        drawPath(
            path = downPath,
            color = Color(0xFF1E88E5),
            style = Stroke(width = 4f)
        )
        drawPath(
            path = upPath,
            color = Color(0xFFE53935),
            style = Stroke(width = 4f)
        )
    }
}

private data class SpeedPoint(
    val downloadBps: Long,
    val uploadBps: Long
)

private fun formatSpeed(bytesPerSecond: Long): String {
    if (bytesPerSecond < 1024L) return "$bytesPerSecond B/s"
    val kb = bytesPerSecond / 1024.0
    if (kb < 1024.0) return String.format("%.2f KB/s", kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return String.format("%.2f MB/s", mb)
    val gb = mb / 1024.0
    return String.format("%.2f GB/s", gb)
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format("%.2f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return String.format("%.2f MB", mb)
    val gb = mb / 1024.0
    return String.format("%.2f GB", gb)
}

private fun networkQualityLabel(snapshot: NetworkSpeedSnapshot): String {
    if (snapshot.probeTotalCount <= 3) return "采样中"
    val loss = snapshot.probeLossRate
    val jitter = snapshot.probeJitterMs
    return when {
        loss > 3.0 || jitter > 50.0 -> "差"
        loss > 1.0 || jitter > 20.0 -> "一般"
        else -> "稳定"
    }
}
