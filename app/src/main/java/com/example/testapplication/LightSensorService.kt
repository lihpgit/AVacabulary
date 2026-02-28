package com.example.testapplication

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlin.math.sqrt

class LightSensorService : Service(), SensorEventListener {

    private val CHANNEL_ID = "LightSensorServiceChannel"
    private val NOTIFICATION_ID = 1

    private lateinit var sensorManager: SensorManager
    private lateinit var cameraManager: CameraManager
    private lateinit var powerManager: PowerManager
    private var wakeLock: PowerManager.WakeLock? = null

    private var lightSensor: Sensor? = null
    private var accelerometer: Sensor? = null

    // Track flashlight state from system
    private var isFlashlightOn = false
    
    private var lastMotionTime = 0L
    private var lastLux = 0f
    
    // 30秒无移动自动关闭
    private val TIMEOUT_MS = 30000L

    // 加速度计算辅助变量
    private var lastAcceleration = 0f
    private var lastUpdate = 0L

    // 协程作用域
    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            super.onTorchModeChanged(cameraId, enabled)
            // Assuming we only care about the first camera (rear)
            try {
                if (cameraId == cameraManager.cameraIdList[0]) {
                    isFlashlightOn = enabled
                }
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        
        lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        // Register Torch Callback to keep state in sync
        cameraManager.registerTorchCallback(torchCallback, Handler(Looper.getMainLooper()))

        // 获取 WakeLock 以确保息屏后 CPU 继续运转（防止传感器休眠）
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyApp:SensorWakeLock")
        wakeLock?.setReferenceCounted(false)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        
        // 点击通知跳转回 Activity
        val notificationIntent = Intent(this, LightSensorActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)

        val notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("智能夜灯运行中")
            .setContentText("正在后台监测光线与移动...")
            .setSmallIcon(android.R.drawable.ic_menu_camera) // 使用系统图标
            .setContentIntent(pendingIntent)
            .setOngoing(true)

        val notification = notificationBuilder.build()

        // 启动前台服务
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                 startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // Fallback
             startForeground(NOTIFICATION_ID, notification)
        }
        
        wakeLock?.acquire(24*60*60*1000L) // 24 hours timeout safety

        // 注册传感器
        lightSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        accelerometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }

        // 启动超时检测循环
        startTimeoutLoop()

        return START_STICKY
    }

    private fun startTimeoutLoop() {
        // 取消之前的 loop 如果有
        serviceScope.launch {
            while (isActive) {
                delay(1000) // 每秒检查一次
                val currentTime = System.currentTimeMillis()
                
                // 如果灯是开着的，且距离上次移动超过30秒 -> 关灯
                if (isFlashlightOn && (currentTime - lastMotionTime > TIMEOUT_MS)) {
                    setFlashlight(false)
                }
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        
        when (event.sensor.type) {
            Sensor.TYPE_LIGHT -> {
                lastLux = event.values[0]
            }
            Sensor.TYPE_ACCELEROMETER -> {
                val curTime = System.currentTimeMillis()
                // 限制检测频率，每100ms检测一次
                if ((curTime - lastUpdate) > 100) {
                    val x = event.values[0]
                    val y = event.values[1]
                    val z = event.values[2]
                    
                    val acceleration = sqrt(x*x + y*y + z*z)
                    val delta = kotlin.math.abs(acceleration - lastAcceleration)
                    lastAcceleration = acceleration
                    lastUpdate = curTime

                    // 阈值 0.5f 比较灵敏，轻轻拿动即可触发
                    if (delta > 0.5f) {
                        lastMotionTime = curTime // 更新最后移动时间
                        
                        // 触发开灯逻辑：环境暗 + 有移动 + 灯没开
                        // 注意：如果灯已经开了，我们只更新 lastMotionTime (已在上面做了)，不需要再次 setFlashlight
                        if (lastLux < 40f && !isFlashlightOn) {
                            setFlashlight(true)
                            // 开灯时也视为一次活动
                            lastMotionTime = curTime 
                        }
                    }
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun setFlashlight(enable: Boolean) {
        // Optimization: check local state first. 
        // Since we use TorchCallback, isFlashlightOn is accurate.
        if (enable == isFlashlightOn) return
        
        try {
            val cameraId = cameraManager.cameraIdList[0]
            cameraManager.setTorchMode(cameraId, enable)
            // Do NOT update isFlashlightOn here manually; wait for callback
            // But callback is async. If we call setFlashlight(true) and immediately check isFlashlightOn, it might be false.
            // However, we only check isFlashlightOn in the next sensor event or loop.
            // It's safer to update it optimistically OR just rely on callback.
            // Relying on callback is safer for state consistency but might have slight latency.
            // Let's update it optimistically too to prevent rapid-fire toggling.
            isFlashlightOn = enable
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            // 确保退出时关灯
            if (isFlashlightOn) {
                val cameraId = cameraManager.cameraIdList[0]
                cameraManager.setTorchMode(cameraId, false)
            }
        } catch (e: Exception) {}

        cameraManager.unregisterTorchCallback(torchCallback)
        sensorManager.unregisterListener(this)
        serviceScope.cancel() // 取消协程
        wakeLock?.release()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Light Sensor Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }
}
