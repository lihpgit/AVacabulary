package com.example.testapplication.vocab

import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.*
import java.io.*
import java.net.*

class SyncActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            com.example.testapplication.ui.theme.TestApplicationTheme {
                SyncScreen()
            }
        }
    }
}

// ── 状态 ───────────────────────────────────────────────────────

private sealed class SyncUiState {
    object Idle : SyncUiState()
    data class Sending(val qrBitmap: Bitmap, val ip: String, val port: Int, val status: String) : SyncUiState()
    data class Receiving(val status: String) : SyncUiState()
    data class Done(val message: String, val isError: Boolean = false) : SyncUiState()
}

// ── 主界面 ─────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SyncScreen() {
    val context = LocalContext.current
    val repository = remember { WordRepository.getInstance(context) }
    val scope = rememberCoroutineScope()

    var state by remember { mutableStateOf<SyncUiState>(SyncUiState.Idle) }
    val serverSocketRef = remember { mutableStateOf<ServerSocket?>(null) }

    // 清理 ServerSocket
    DisposableEffect(Unit) {
        onDispose {
            try { serverSocketRef.value?.close() } catch (_: Exception) {}
        }
    }

    // 扫码启动器
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val content = result.contents
        if (content.isNullOrBlank()) {
            state = SyncUiState.Idle
            return@rememberLauncherForActivityResult
        }
        // 解析 ip:port
        val parts = content.split(":")
        if (parts.size != 2) {
            state = SyncUiState.Done("二维码格式无效", isError = true)
            return@rememberLauncherForActivityResult
        }
        val ip = parts[0]
        val port = parts[1].toIntOrNull()
        if (port == null) {
            state = SyncUiState.Done("二维码端口无效", isError = true)
            return@rememberLauncherForActivityResult
        }
        // 连接并接收
        state = SyncUiState.Receiving("正在连接 $ip:$port ...")
        scope.launch {
            receiveProgress(ip, port, repository) { msg, err ->
                state = SyncUiState.Done(msg, isError = err)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("同步学习进度") },
                navigationIcon = {
                    IconButton(onClick = {
                        try { serverSocketRef.value?.close() } catch (_: Exception) {}
                        (context as? SyncActivity)?.finish()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            when (val s = state) {
                is SyncUiState.Idle -> {
                    Text(
                        "确保两部手机连接同一 WiFi",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(32.dp))
                    Button(
                        onClick = {
                            scope.launch {
                                startSending(repository, serverSocketRef) { newState ->
                                    state = newState
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("发送进度（本机 → 对方）", fontSize = 16.sp)
                    }
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(
                        onClick = {
                            state = SyncUiState.Receiving("准备扫码...")
                            scanLauncher.launch(ScanOptions().apply {
                                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                setPrompt("扫描发送方手机上的二维码")
                                setBeepEnabled(false)
                                setOrientationLocked(true)
                            })
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("接收进度（扫码接收）", fontSize = 16.sp)
                    }
                }

                is SyncUiState.Sending -> {
                    Text(
                        "请用另一部手机扫描下方二维码",
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(16.dp))
                    Image(
                        bitmap = s.qrBitmap.asImageBitmap(),
                        contentDescription = "同步二维码",
                        modifier = Modifier.size(240.dp)
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "IP: ${s.ip}  端口: ${s.port}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        s.status,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (s.status.contains("等待")) {
                        Spacer(Modifier.height(12.dp))
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                    Spacer(Modifier.height(24.dp))
                    OutlinedButton(onClick = {
                        try { serverSocketRef.value?.close() } catch (_: Exception) {}
                        state = SyncUiState.Idle
                    }) {
                        Text("取消")
                    }
                }

                is SyncUiState.Receiving -> {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
                    Spacer(Modifier.height(16.dp))
                    Text(s.status, style = MaterialTheme.typography.bodyMedium)
                }

                is SyncUiState.Done -> {
                    Text(
                        text = if (s.isError) "同步失败" else "同步完成",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (s.isError) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        s.message,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = { state = SyncUiState.Idle }) {
                        Text("返回")
                    }
                }
            }
        }
    }
}

// ── 发送端逻辑 ─────────────────────────────────────────────────

private suspend fun startSending(
    repository: WordRepository,
    serverSocketRef: MutableState<ServerSocket?>,
    onState: (SyncUiState) -> Unit
) {
    withContext(Dispatchers.IO) {
        val ip = getLocalIp()
        if (ip == null) {
            withContext(Dispatchers.Main) {
                onState(SyncUiState.Done("无法获取本机 IP，请检查 WiFi 连接", isError = true))
            }
            return@withContext
        }

        val server = try { ServerSocket(0) } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                onState(SyncUiState.Done("启动服务失败: ${e.message}", isError = true))
            }
            return@withContext
        }
        serverSocketRef.value = server
        val port = server.localPort
        val qrContent = "$ip:$port"
        val qrBitmap = generateQR(qrContent, 512)

        withContext(Dispatchers.Main) {
            onState(SyncUiState.Sending(qrBitmap, ip, port, "等待对方扫码连接..."))
        }

        // 等待连接
        val socket = try {
            server.soTimeout = 120_000 // 2 分钟超时
            server.accept()
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                val msg = if (e is SocketTimeoutException) "等待超时，请重试" else "连接失败: ${e.message}"
                onState(SyncUiState.Done(msg, isError = true))
            }
            try { server.close() } catch (_: Exception) {}
            return@withContext
        }

        withContext(Dispatchers.Main) {
            onState(SyncUiState.Sending(qrBitmap, ip, port, "已连接，正在发送..."))
        }

        // 发送数据
        try {
            val json = repository.exportProgress()
            val bytes = json.toByteArray(Charsets.UTF_8)
            socket.getOutputStream().apply {
                // 先发 4 字节长度（大端）
                write(bytes.size shr 24 and 0xFF)
                write(bytes.size shr 16 and 0xFF)
                write(bytes.size shr 8 and 0xFF)
                write(bytes.size and 0xFF)
                write(bytes)
                flush()
            }
            socket.close()
            server.close()
            withContext(Dispatchers.Main) {
                onState(SyncUiState.Done("进度已发送成功"))
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                onState(SyncUiState.Done("发送失败: ${e.message}", isError = true))
            }
        }
    }
}

// ── 接收端逻辑 ─────────────────────────────────────────────────

private suspend fun receiveProgress(
    ip: String,
    port: Int,
    repository: WordRepository,
    onDone: (String, Boolean) -> Unit
) {
    withContext(Dispatchers.IO) {
        try {
            val socket = Socket()
            socket.connect(InetSocketAddress(ip, port), 10_000)
            val input = socket.getInputStream()

            // 读 4 字节长度
            val lenBytes = ByteArray(4)
            var read = 0
            while (read < 4) {
                val n = input.read(lenBytes, read, 4 - read)
                if (n < 0) throw IOException("连接中断")
                read += n
            }
            val length = (lenBytes[0].toInt() and 0xFF shl 24) or
                    (lenBytes[1].toInt() and 0xFF shl 16) or
                    (lenBytes[2].toInt() and 0xFF shl 8) or
                    (lenBytes[3].toInt() and 0xFF)

            if (length <= 0 || length > 10 * 1024 * 1024) {
                throw IOException("数据大小异常: $length bytes")
            }

            // 读 JSON 数据
            val data = ByteArray(length)
            read = 0
            while (read < length) {
                val n = input.read(data, read, length - read)
                if (n < 0) throw IOException("数据接收不完整")
                read += n
            }
            socket.close()

            val json = String(data, Charsets.UTF_8)
            withContext(Dispatchers.Main) {
                repository.importProgress(json)
                onDone("已成功接收并覆盖本地进度", false)
            }
        } catch (e: Exception) {
            val msg = when (e) {
                is SocketTimeoutException -> "连接超时，请确认同一 WiFi"
                is ConnectException -> "无法连接发送方，请确认二维码有效"
                else -> "接收失败: ${e.message}"
            }
            withContext(Dispatchers.Main) { onDone(msg, true) }
        }
    }
}

// ── 工具方法 ───────────────────────────────────────────────────

private fun getLocalIp(): String? {
    try {
        NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { intf ->
            intf.inetAddresses?.toList()?.forEach { addr ->
                if (!addr.isLoopbackAddress && addr is Inet4Address) {
                    return addr.hostAddress
                }
            }
        }
    } catch (_: Exception) {}
    return null
}

private fun generateQR(content: String, size: Int): Bitmap {
    val writer = QRCodeWriter()
    val matrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size)
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
    for (x in 0 until size) {
        for (y in 0 until size) {
            bitmap.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }
    }
    return bitmap
}
