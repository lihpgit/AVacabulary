package com.example.testapplication.vocab

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
        // 解析 ip:port（ip 为 GW 时表示发送方是热点主机，用本机网关回连）
        val parts = content.split(":")
        if (parts.size != 2) {
            state = SyncUiState.Done("二维码格式无效", isError = true)
            return@rememberLauncherForActivityResult
        }
        val port = parts[1].toIntOrNull()
        if (port == null) {
            state = SyncUiState.Done("二维码端口无效", isError = true)
            return@rememberLauncherForActivityResult
        }
        val ip = if (parts[0] == "GW") {
            getGatewayIp(context) ?: run {
                state = SyncUiState.Done("无法获取热点网关地址，请确认本机已连接发送方的热点", isError = true)
                return@rememberLauncherForActivityResult
            }
        } else {
            parts[0]
        }
        // 连接并接收
        state = SyncUiState.Receiving("正在连接 $ip:$port ...")
        scope.launch {
            receiveProgress(ip, port, repository) { msg, err ->
                state = SyncUiState.Done(msg, isError = err)
            }
        }
    }

    // 从文件导入进度（接收方：收到分享的文件后选中导入）
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) {
            state = SyncUiState.Idle
            return@rememberLauncherForActivityResult
        }
        state = SyncUiState.Receiving("正在读取文件...")
        scope.launch {
            try {
                val json = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    }
                }
                if (json.isNullOrBlank()) {
                    state = SyncUiState.Done("文件为空或无法读取", isError = true)
                    return@launch
                }
                // 去掉开头的 UTF-8 BOM 和空白，避免 JSON 解析报错
                val cleaned = json.dropWhile { it.code == 0xFEFF || it.isWhitespace() }
                repository.importProgress(cleaned)
                state = SyncUiState.Done("已从文件导入并覆盖本地进度", isError = false)
            } catch (e: Exception) {
                state = SyncUiState.Done("导入失败: ${e.message}", isError = true)
            }
        }
    }

    // 分享进度文件（发送方：导出 JSON → 系统分享面板，可选蓝牙/微信/快传等）
    fun shareProgressFile() {
        scope.launch {
            try {
                val uri = withContext(Dispatchers.IO) {
                    val json = repository.exportProgress()
                    val dir = File(context.cacheDir, "share").apply { mkdirs() }
                    dir.listFiles()?.forEach { it.delete() } // 清理旧文件
                    val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                        .format(java.util.Date())
                    val file = File(dir, "vocab_progress_$ts.json")
                    file.writeText(json)
                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                }
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "*/*"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "背单词学习进度")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(send, "分享学习进度（蓝牙/微信/快传…）"))
            } catch (e: Exception) {
                state = SyncUiState.Done("分享失败: ${e.message}", isError = true)
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
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            when (val s = state) {
                is SyncUiState.Idle -> {
                    // ── 方式一：文件分享（最稳，含蓝牙/微信/快传，零网络要求）──
                    Text(
                        "方式一 · 文件分享（推荐）",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "发送方分享进度文件，可选蓝牙/微信/QQ/快传等；接收方收到后从文件导入。无需同一网络。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { shareProgressFile() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("分享进度文件（蓝牙/微信/快传…）", fontSize = 16.sp)
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = { importLauncher.launch(arrayOf("*/*")) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("从文件导入进度", fontSize = 16.sp)
                    }

                    Spacer(Modifier.height(24.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(24.dp))

                    // ── 方式二：同一 WiFi/热点 扫码直传 ──
                    Text(
                        "方式二 · 扫码直传",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "两机连同一 WiFi，或一方开热点另一方连接，扫码即时传输",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            scope.launch {
                                startSending(context, repository, serverSocketRef) { newState ->
                                    state = newState
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("发送进度（出二维码）", fontSize = 16.sp)
                    }
                    Spacer(Modifier.height(12.dp))
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
    context: Context,
    repository: WordRepository,
    serverSocketRef: MutableState<ServerSocket?>,
    onState: (SyncUiState) -> Unit
) {
    withContext(Dispatchers.IO) {
        val ip = getLocalIp(context)

        val server = try { ServerSocket(0) } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                onState(SyncUiState.Done("启动服务失败: ${e.message}", isError = true))
            }
            return@withContext
        }
        serverSocketRef.value = server
        val port = server.localPort
        // 拿不到本机局域网 IP（多为本机开热点、AP 网卡不可枚举）时，
        // 用哨兵 GW：让接收方用自己的网关地址（=本机）回连。
        // ServerSocket 绑定全部网卡（含 AP），即使不知道自己 IP 也能被连上。
        val qrContent = if (ip != null) "$ip:$port" else "GW:$port"
        val displayIp = ip ?: "热点网关（对方将自动识别）"
        val qrBitmap = generateQR(qrContent, 512)

        withContext(Dispatchers.Main) {
            onState(SyncUiState.Sending(qrBitmap, displayIp, port, "等待对方扫码连接..."))
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
            onState(SyncUiState.Sending(qrBitmap, displayIp, port, "已连接，正在发送..."))
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
                is SocketTimeoutException -> "连接超时，请确认两机在同一 WiFi 或热点下"
                is ConnectException -> "无法连接发送方，请确认二维码有效"
                else -> "接收失败: ${e.message}"
            }
            withContext(Dispatchers.Main) { onDone(msg, true) }
        }
    }
}

// ── 工具方法 ───────────────────────────────────────────────────

private fun getLocalIp(context: Context): String? {
    // 遍历所有网卡按类型打分选最佳：
    // 排除蜂窝/VPN（这些地址对方连不上）；优先热点 AP 网卡，其次常规 WiFi。
    // 关键：本机作热点时，AP 网卡（ap0/wlan1/swlan0，常见 192.168.43.1）并非 activeNetwork，
    // 不能用 ConnectivityManager.activeNetwork，否则会错填成蜂窝 IP 导致对方连不上。
    try {
        var bestIp: String? = null
        var bestScore = -1
        NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { intf ->
            if (!intf.isUp || intf.isLoopback) return@forEach
            val name = intf.name?.lowercase() ?: ""
            // 排除蜂窝、VPN、点对点等对端不可达的接口
            if (name.startsWith("rmnet") || name.startsWith("ccmni") ||
                name.startsWith("pdp") || name.startsWith("ppp") ||
                name.startsWith("tun") || name.startsWith("clat")
            ) return@forEach
            intf.inetAddresses?.toList()?.forEach inner@{ addr ->
                if (addr !is Inet4Address || addr.isLoopbackAddress) return@inner
                val host = addr.hostAddress ?: return@inner
                val score = when {
                    // 本机热点 AP 接口（对方连本机热点时的网关 IP）
                    name.contains("ap0") || name.contains("swlan") ||
                        name.contains("softap") || name.contains("wlan1") -> 100
                    // 常规 WiFi（含连接别人热点时的 wlan0）
                    name.startsWith("wlan") || name.startsWith("eth") -> 90
                    // USB/其它网络共享
                    name.startsWith("rndis") || name.startsWith("tether") ||
                        name.startsWith("usb") -> 80
                    // 私有网段兜底
                    addr.isSiteLocalAddress -> 50
                    else -> 10
                }
                if (score > bestScore) {
                    bestScore = score
                    bestIp = host
                }
            }
        }
        if (bestIp != null) return bestIp
    } catch (_: Exception) {}
    // 兜底：ConnectivityManager 活跃网络
    try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val lp = cm?.getLinkProperties(cm.activeNetwork ?: return null)
        lp?.linkAddresses?.forEach { la ->
            val addr = la.address
            if (addr is Inet4Address && !addr.isLoopbackAddress) {
                return addr.hostAddress
            }
        }
    } catch (_: Exception) {}
    return null
}


/**
 * 获取本机所连 WiFi/热点的网关 IP。
 * 当接收方连接到发送方开的热点时，网关地址即发送方（热点主机）的地址。
 */
@Suppress("DEPRECATION")
private fun getGatewayIp(context: Context): String? {
    return try {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val gw = wifi?.dhcpInfo?.gateway ?: 0
        if (gw == 0) null
        // DhcpInfo 的 int 为小端序
        else "${gw and 0xFF}.${gw shr 8 and 0xFF}.${gw shr 16 and 0xFF}.${gw shr 24 and 0xFF}"
    } catch (_: Exception) {
        null
    }
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
