package com.example.testapplication

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.TrafficStats
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.random.Random
import kotlin.math.max

data class NetworkSpeedSnapshot(
    val timestampMs: Long = System.currentTimeMillis(),
    val uidRxBytes: Long = 0L,
    val uidTxBytes: Long = 0L,
    val downloadSpeedBps: Long = 0L,
    val uploadSpeedBps: Long = 0L,
    val totalDownloadedBytes: Long = 0L,
    val totalUploadedBytes: Long = 0L,
    val isDownloadRunning: Boolean = false,
    val isUploadRunning: Boolean = false,
    val isProbeRunning: Boolean = false,
    val probeTotalCount: Long = 0L,
    val probeLossCount: Long = 0L,
    val probeLossRate: Double = 0.0,
    val probeAvgRttMs: Double = 0.0,
    val probeJitterMs: Double = 0.0,
    val probeLastRttMs: Long = -1L,
    val lastError: String? = null
)

class NetworkSpeedService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): NetworkSpeedService = this@NetworkSpeedService
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val appUid by lazy { applicationInfo.uid }
    private val notificationManager by lazy {
        getSystemService(NotificationManager::class.java)
    }

    private val downloadedBytes = AtomicLong(0L)
    private val uploadedBytes = AtomicLong(0L)

    private var downloadJob: Job? = null
    private var uploadJob: Job? = null
    private var probeJob: Job? = null
    private var samplerJob: Job? = null
    private var isInForeground = false
    private val probeWindowRtts = ArrayDeque<Long>()
    private val probeTotalCounter = AtomicLong(0L)
    private val probeLossCounter = AtomicLong(0L)

    private val _stats = MutableStateFlow(NetworkSpeedSnapshot())
    val stats: StateFlow<NetworkSpeedSnapshot> = _stats.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        startSpeedSampler()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SERVICE -> {
                stopAllTests()
                if (isInForeground) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    isInForeground = false
                }
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopAllTests()
        samplerJob?.cancel()
        if (isInForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isInForeground = false
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    fun startDownloadTest(url: String = DEFAULT_DOWNLOAD_URL) {
        val target = url.trim()
        if (target.isEmpty()) {
            updateError("下载地址为空")
            return
        }
        if (downloadJob?.isActive == true) return

        updateError(null)
        ensureForeground()
        _stats.update { it.copy(isDownloadRunning = true) }
        downloadJob = serviceScope.launch {
            try {
                runDownloadLoop(target)
            } finally {
                _stats.update { it.copy(isDownloadRunning = false) }
                maybeStopForegroundAndSelfIfIdle()
            }
        }
    }

    fun stopDownloadTest() {
        downloadJob?.cancel()
        downloadJob = null
        _stats.update { it.copy(isDownloadRunning = false) }
    }

    fun startUploadTest(url: String = DEFAULT_UPLOAD_URL) {
        val target = url.trim()
        if (target.isEmpty()) {
            updateError("上传地址为空")
            return
        }
        if (uploadJob?.isActive == true) return

        updateError(null)
        ensureForeground()
        _stats.update { it.copy(isUploadRunning = true) }
        uploadJob = serviceScope.launch {
            try {
                runUploadLoop(target)
            } finally {
                _stats.update { it.copy(isUploadRunning = false) }
                maybeStopForegroundAndSelfIfIdle()
            }
        }
    }

    fun stopUploadTest() {
        uploadJob?.cancel()
        uploadJob = null
        _stats.update { it.copy(isUploadRunning = false) }
    }

    fun startProbeTest(
        url: String = DEFAULT_PROBE_URL,
        intervalMs: Long = DEFAULT_PROBE_INTERVAL_MS,
        timeoutMs: Int = DEFAULT_PROBE_TIMEOUT_MS
    ) {
        val target = url.trim()
        if (target.isEmpty()) {
            updateError("探测地址为空")
            return
        }
        if (probeJob?.isActive == true) return

        updateError(null)
        ensureForeground()
        _stats.update { it.copy(isProbeRunning = true) }
        probeJob = serviceScope.launch {
            try {
                runProbeLoop(
                    url = target,
                    intervalMs = intervalMs.coerceAtLeast(300L),
                    timeoutMs = timeoutMs.coerceAtLeast(300)
                )
            } finally {
                _stats.update { it.copy(isProbeRunning = false) }
                maybeStopForegroundAndSelfIfIdle()
            }
        }
    }

    fun stopProbeTest() {
        probeJob?.cancel()
        probeJob = null
        _stats.update { it.copy(isProbeRunning = false) }
    }

    fun resetProbeStats() {
        probeTotalCounter.set(0L)
        probeLossCounter.set(0L)
        synchronized(probeWindowRtts) {
            probeWindowRtts.clear()
        }
        _stats.update {
            it.copy(
                probeTotalCount = 0L,
                probeLossCount = 0L,
                probeLossRate = 0.0,
                probeAvgRttMs = 0.0,
                probeJitterMs = 0.0,
                probeLastRttMs = -1L
            )
        }
    }

    fun stopAllTests() {
        stopDownloadTest()
        stopUploadTest()
        stopProbeTest()
    }

    fun stopSelfIfIdle() {
        maybeStopForegroundAndSelfIfIdle()
    }

    private fun startSpeedSampler() {
        samplerJob?.cancel()
        samplerJob = serviceScope.launch {
            var lastRx = readUidRxBytes()
            var lastTx = readUidTxBytes()
            var lastSampleMs = SystemClock.elapsedRealtime()

            while (isActive) {
                delay(1000)

                val nowMs = SystemClock.elapsedRealtime()
                val nowRx = readUidRxBytes()
                val nowTx = readUidTxBytes()
                val elapsed = max(1L, nowMs - lastSampleMs)

                val rxSpeed = ((nowRx - lastRx).coerceAtLeast(0L) * 1000L) / elapsed
                val txSpeed = ((nowTx - lastTx).coerceAtLeast(0L) * 1000L) / elapsed

                _stats.update {
                    it.copy(
                        timestampMs = System.currentTimeMillis(),
                        uidRxBytes = nowRx,
                        uidTxBytes = nowTx,
                        downloadSpeedBps = rxSpeed,
                        uploadSpeedBps = txSpeed,
                        totalDownloadedBytes = downloadedBytes.get(),
                        totalUploadedBytes = uploadedBytes.get()
                    )
                }
                if (isInForeground) {
                    notificationManager.notify(NOTIFICATION_ID, buildNotification(_stats.value))
                }

                lastRx = nowRx
                lastTx = nowTx
                lastSampleMs = nowMs
            }
        }
    }

    private suspend fun runDownloadLoop(url: String) {
        while (currentCoroutineContext().isActive) {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 15000
                    requestMethod = "GET"
                    useCaches = false
                    doInput = true
                    instanceFollowRedirects = true
                }

                BufferedInputStream(connection.inputStream).use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (currentCoroutineContext().isActive) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        downloadedBytes.addAndGet(read.toLong())
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateError("下载异常: ${e.message}")
                delay(1000)
            } finally {
                connection?.disconnect()
            }

            if (!currentCoroutineContext().isActive) break
        }
    }

    private suspend fun runUploadLoop(url: String) {
        val payload = ByteArray(256 * 1024).also { Random.Default.nextBytes(it) }

        while (currentCoroutineContext().isActive) {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 15000
                    requestMethod = "POST"
                    useCaches = false
                    doOutput = true
                    doInput = true
                    setRequestProperty("Content-Type", "application/octet-stream")
                    setChunkedStreamingMode(0)
                    instanceFollowRedirects = true
                }

                connection.outputStream.use { output ->
                    repeat(8) {
                        if (!currentCoroutineContext().isActive) return@repeat
                        output.write(payload)
                        uploadedBytes.addAndGet(payload.size.toLong())
                    }
                    output.flush()
                }

                val responseCode = connection.responseCode
                val responseStream = if (responseCode in 200..299) {
                    connection.inputStream
                } else {
                    updateError("上传HTTP错误: $responseCode")
                    connection.errorStream
                }
                responseStream?.use { it.readBytes() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateError("上传异常: ${e.message}")
                delay(1000)
            } finally {
                connection?.disconnect()
            }

            if (!currentCoroutineContext().isActive) break
        }
    }

    private suspend fun runProbeLoop(url: String, intervalMs: Long, timeoutMs: Int) {
        while (currentCoroutineContext().isActive) {
            val startMs = SystemClock.elapsedRealtime()
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                    requestMethod = "GET"
                    useCaches = false
                    doInput = true
                    instanceFollowRedirects = true
                    setRequestProperty("Cache-Control", "no-cache")
                }

                val code = connection.responseCode
                if (code in 200..399) {
                    connection.inputStream.use { input ->
                        val sink = ByteArray(64)
                        input.read(sink)
                    }
                    val rttMs = max(1L, SystemClock.elapsedRealtime() - startMs)
                    recordProbeResult(success = true, rttMs = rttMs)
                } else {
                    updateError("探测HTTP错误: $code")
                    recordProbeResult(success = false, rttMs = null)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateError("探测异常: ${e.message}")
                recordProbeResult(success = false, rttMs = null)
            } finally {
                connection?.disconnect()
            }

            val spentMs = SystemClock.elapsedRealtime() - startMs
            val delayMs = (intervalMs - spentMs).coerceAtLeast(0L)
            if (delayMs > 0) delay(delayMs)
        }
    }

    private fun readUidRxBytes(): Long {
        val value = TrafficStats.getUidRxBytes(appUid)
        return if (value == TrafficStats.UNSUPPORTED.toLong()) 0L else value
    }

    private fun readUidTxBytes(): Long {
        val value = TrafficStats.getUidTxBytes(appUid)
        return if (value == TrafficStats.UNSUPPORTED.toLong()) 0L else value
    }

    private fun updateError(message: String?) {
        _stats.update { it.copy(lastError = message) }
    }

    private fun recordProbeResult(success: Boolean, rttMs: Long?) {
        val total = probeTotalCounter.incrementAndGet()
        val loss = if (success) probeLossCounter.get() else probeLossCounter.incrementAndGet()

        var avgRtt = 0.0
        var jitter = 0.0
        val lastRtt = rttMs ?: _stats.value.probeLastRttMs

        if (success && rttMs != null) {
            synchronized(probeWindowRtts) {
                probeWindowRtts.addLast(rttMs)
                while (probeWindowRtts.size > PROBE_WINDOW_SIZE) {
                    probeWindowRtts.removeFirst()
                }

                if (probeWindowRtts.isNotEmpty()) {
                    avgRtt = probeWindowRtts.average()
                    if (probeWindowRtts.size > 1) {
                        var deltaSum = 0.0
                        var prev: Long? = null
                        probeWindowRtts.forEach { current ->
                            val p = prev
                            if (p != null) deltaSum += abs(current - p).toDouble()
                            prev = current
                        }
                        jitter = deltaSum / (probeWindowRtts.size - 1)
                    }
                }
            }
        } else {
            synchronized(probeWindowRtts) {
                if (probeWindowRtts.isNotEmpty()) {
                    avgRtt = probeWindowRtts.average()
                    if (probeWindowRtts.size > 1) {
                        var deltaSum = 0.0
                        var prev: Long? = null
                        probeWindowRtts.forEach { current ->
                            val p = prev
                            if (p != null) deltaSum += abs(current - p).toDouble()
                            prev = current
                        }
                        jitter = deltaSum / (probeWindowRtts.size - 1)
                    }
                }
            }
        }

        val lossRate = if (total == 0L) 0.0 else (loss.toDouble() * 100.0) / total.toDouble()
        _stats.update {
            it.copy(
                probeTotalCount = total,
                probeLossCount = loss,
                probeLossRate = lossRate,
                probeAvgRttMs = avgRtt,
                probeJitterMs = jitter,
                probeLastRttMs = lastRtt
            )
        }
    }

    private fun ensureForeground() {
        if (isInForeground) return
        createNotificationChannelIfNeeded()
        val notification = buildNotification(_stats.value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        isInForeground = true
    }

    private fun maybeStopForegroundAndSelfIfIdle() {
        val running = (downloadJob?.isActive == true) ||
            (uploadJob?.isActive == true) ||
            (probeJob?.isActive == true)
        if (running) return
        if (isInForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isInForeground = false
        }
        stopSelf()
    }

    private fun createNotificationChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (notificationManager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Network Speed Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "网络测速前台服务"
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(snapshot: NetworkSpeedSnapshot): Notification {
        val openIntent = Intent(this, NetworkSpeedActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            1001,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val probeText = if (snapshot.isProbeRunning) {
            " | 丢包 ${String.format("%.1f%%", snapshot.probeLossRate)}"
        } else {
            ""
        }
        val content = "下载 ${formatSpeed(snapshot.downloadSpeedBps)} | 上传 ${formatSpeed(snapshot.uploadSpeedBps)}$probeText"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("网络测速进行中")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun formatSpeed(bytesPerSecond: Long): String {
        if (bytesPerSecond < 1024L) return "${bytesPerSecond}B/s"
        val kb = bytesPerSecond / 1024.0
        if (kb < 1024.0) return String.format("%.1fKB/s", kb)
        val mb = kb / 1024.0
        return String.format("%.2fMB/s", mb)
    }

    companion object {
        private const val CHANNEL_ID = "network_speed_service_channel"
        private const val NOTIFICATION_ID = 1101
        private const val PROBE_WINDOW_SIZE = 60
        private const val DEFAULT_PROBE_INTERVAL_MS = 1000L
        private const val DEFAULT_PROBE_TIMEOUT_MS = 1500
        const val ACTION_STOP_SERVICE = "com.example.testapplication.action.NETWORK_SPEED_STOP"
        const val DEFAULT_DOWNLOAD_URL = "https://speed.cloudflare.com/__down?bytes=20000000"
        const val DEFAULT_UPLOAD_URL = "https://speed.cloudflare.com/__up"
        const val DEFAULT_PROBE_URL = "https://www.gstatic.com/generate_204"
    }
}
