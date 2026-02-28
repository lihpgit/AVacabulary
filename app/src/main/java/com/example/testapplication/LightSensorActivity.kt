package com.example.testapplication

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.sqrt

class LightSensorActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LightSensorView()
        }
    }
}

@Suppress("DEPRECATION")
fun isServiceRunning(context: Context, serviceClass: Class<*>): Boolean {
    val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    for (service in manager.getRunningServices(Int.MAX_VALUE)) {
        if (serviceClass.name == service.service.className) {
            return true
        }
    }
    return false
}

@Composable
fun LightSensorView() {
    val context = LocalContext.current
    val sensorManager = remember { context.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    val lightSensor = remember { sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT) }
    // Accelerometer is now handled by Service for Motion Mode
    val cameraManager = remember { context.getSystemService(Context.CAMERA_SERVICE) as CameraManager }
    val scope = rememberCoroutineScope()
    
    // Sensor values
    var luxValue by remember { mutableStateOf(0f) }
    var soundDb by remember { mutableStateOf(0.0) }
    var sensorStatus by remember { mutableStateOf("等待传感器...") }
    
    // Modes
    var isAutoNightMode by remember { mutableStateOf(false) } // 持续夜灯
    var isMotionMode by remember { mutableStateOf(false) }    // 移动感应 (后台服务)
    var isSoundMode by remember { mutableStateOf(false) }     // 声音感应
    
    // Settings
    var soundThreshold by remember { mutableStateOf(35f) }    // 声音阈值 (默认 35dB)
    var brightnessLevel by remember { mutableStateOf(1) }     // 亮度等级 (1 ~ Max)
    var maxBrightnessLevel by remember { mutableStateOf(1) }
    
    // State
    var isFlashlightOn by remember { mutableStateOf(false) }
    var autoOffTimerJob by remember { mutableStateOf<Job?>(null) }
    var hasRecordPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }

    // Check Service Status
    LaunchedEffect(Unit) {
        isMotionMode = isServiceRunning(context, LightSensorService::class.java)
    }

    // Initialize Max Brightness
    LaunchedEffect(Unit) {
        try {
            val cameraId = cameraManager.cameraIdList[0]
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                maxBrightnessLevel = characteristics.get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL) ?: 1
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Permission Launcher
    val requestPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // Handle RECORD_AUDIO for Sound Mode
        if (permissions.containsKey(Manifest.permission.RECORD_AUDIO)) {
            val granted = permissions[Manifest.permission.RECORD_AUDIO] == true
            hasRecordPermission = granted
            if (!granted) isSoundMode = false
        }
        
        // Handle POST_NOTIFICATIONS for Motion Mode
        if (permissions.containsKey(Manifest.permission.POST_NOTIFICATIONS)) {
            val granted = permissions[Manifest.permission.POST_NOTIFICATIONS] == true
            if (granted) {
                // 用户授权后，更新状态，触发 Side Effect 启动服务
                isMotionMode = true
            } else {
                Toast.makeText(context, "通知权限被拒绝，无法开启后台模式", Toast.LENGTH_SHORT).show()
                isMotionMode = false
            }
        }
    }
    
    // Handle Motion Mode Toggle
    fun toggleMotionMode(enable: Boolean) {
        if (enable) {
            // Android 13+ 需要动态请求通知权限
            if (Build.VERSION.SDK_INT >= 33) {
                 if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                     requestPermissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                     return 
                 }
            }
            isMotionMode = true
        } else {
            isMotionMode = false
        }
    }
    
    // Service Control Side Effect
    LaunchedEffect(isMotionMode) {
        val intent = Intent(context, LightSensorService::class.java)
        if (isMotionMode) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                     context.startForegroundService(intent)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            } else {
                context.startService(intent)
            }
        } else {
            context.stopService(intent)
        }
    }

    // Flashlight Control
    fun setFlashlight(enable: Boolean) {
        if (enable == isFlashlightOn && !enable) return // Optimize off calls
        // If Service is running, it might also control flashlight.
        // We do not prevent local control, but be aware of conflicts.
        
        try {
            val cameraId = cameraManager.cameraIdList[0]
            if (enable) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && maxBrightnessLevel > 1) {
                    cameraManager.turnOnTorchWithStrengthLevel(cameraId, brightnessLevel)
                } else {
                    cameraManager.setTorchMode(cameraId, true)
                }
            } else {
                cameraManager.setTorchMode(cameraId, false)
            }
            isFlashlightOn = enable
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Trigger Logic (Sound only now)
    fun triggerAutoLight() {
        if (!isFlashlightOn) {
            setFlashlight(true)
        }
        // Reset timer
        autoOffTimerJob?.cancel()
        autoOffTimerJob = scope.launch {
            delay(5000) // 5 seconds
            setFlashlight(false)
        }
    }

    // Sound Meter Logic
    LaunchedEffect(isSoundMode, hasRecordPermission) {
        if (isSoundMode) {
            if (!hasRecordPermission) {
                requestPermissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            } else {
                startNoiseMeter { dbfs ->
                    val spl = max(0.0, dbfs + 110.0)
                    soundDb = spl
                    if (luxValue < 40f && spl > soundThreshold) {
                        triggerAutoLight()
                    }
                }
            }
        } else {
            soundDb = 0.0
        }
    }

    // Mode Exclusion Logic
    LaunchedEffect(isAutoNightMode) {
        if (isAutoNightMode) {
            // Motion Mode (Service) can coexist if user wants, but typically we might want to disable it?
            // User requested Motion Mode to be background. Auto Night Mode is foreground.
            // Let's keep them independent for now.
            isSoundMode = false
        } else if (isFlashlightOn && !isMotionMode && !isSoundMode) {
            // Check logic: if local modes are off, turn off local flashlight control.
            // But Service runs independently.
            if (!isServiceRunning(context, LightSensorService::class.java)) {
                setFlashlight(false)
            }
        }
    }
    
    
    // Update brightness in real-time if light is on
    LaunchedEffect(brightnessLevel) {
        if (isFlashlightOn) {
            setFlashlight(true) // Re-apply with new brightness
        }
    }

    DisposableEffect(Unit) {
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                if (event?.sensor?.type == Sensor.TYPE_LIGHT) {
                    val lux = event.values[0]
                    luxValue = lux
                    sensorStatus = "正在监测"
                    
                    // Mode 1: Auto Night Mode (Continuous)
                    if (isAutoNightMode) {
                        if (lux < 40f && !isFlashlightOn) setFlashlight(true)
                        else if (lux > 50f && isFlashlightOn) setFlashlight(false)
                    }
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        if (lightSensor != null) sensorManager.registerListener(listener, lightSensor, SensorManager.SENSOR_DELAY_UI)
        // No longer listening to Accelerometer here

        onDispose {
            sensorManager.unregisterListener(listener)
            autoOffTimerJob?.cancel()
            // If Service is NOT running, turn off light. 
            // If Service IS running, let it decide? 
            // Better to turn off local control. Service manages its own light state (though it's the same hardware).
            if (!isMotionMode && isFlashlightOn) {
                try {
                    val cameraId = cameraManager.cameraIdList[0]
                    cameraManager.setTorchMode(cameraId, false)
                } catch (e: Exception) {}
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("智能感应照明", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(20.dp))
        
        // Status Display
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "${luxValue.toInt()}",
                    style = MaterialTheme.typography.displayMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text("光照 (lux)", style = MaterialTheme.typography.bodySmall)
            }
            if (isSoundMode) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "${soundDb.toInt()}",
                        style = MaterialTheme.typography.displayMedium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Text("噪音 (dB)", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // Mode 1: Continuous Night Light
        ModeCard(
            title = "常驻夜灯模式",
            desc = "光线 < 40 lux 时持续开启",
            checked = isAutoNightMode,
            onCheckedChange = { isAutoNightMode = it }
        )

        // Mode 2: Motion Sensor (Background Service)
        ModeCard(
            title = "移动感应模式 (后台)",
            desc = "光线暗+移动开启 (30秒无移动关闭)\n前后台均可运行，通知栏保活",
            checked = isMotionMode,
            onCheckedChange = { toggleMotionMode(it) }
        )

        // Mode 3: Sound Sensor
        ModeCard(
            title = "声控感应模式",
            desc = "暗处声音 > ${soundThreshold.toInt()}dB 开启 5 秒",
            checked = isSoundMode,
            onCheckedChange = { isSoundMode = it }
        ) {
             if (isSoundMode) {
                 Column(modifier = Modifier.padding(top = 8.dp)) {
                     Text("触发阈值: ${soundThreshold.toInt()} dB", style = MaterialTheme.typography.bodyMedium)
                     Slider(
                         value = soundThreshold,
                         onValueChange = { soundThreshold = it },
                         valueRange = 20f..90f
                     )
                 }
             }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Global Settings: Brightness
        Card(
             colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
             modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("手电筒亮度设置", style = MaterialTheme.typography.titleMedium)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && maxBrightnessLevel > 1) {
                    Text("当前等级: $brightnessLevel / $maxBrightnessLevel", style = MaterialTheme.typography.bodySmall)
                    Slider(
                        value = brightnessLevel.toFloat(),
                        onValueChange = { brightnessLevel = it.toInt() },
                        valueRange = 1f..maxBrightnessLevel.toFloat(),
                        steps = maxBrightnessLevel - 1
                    )
                } else {
                    Text("当前设备不支持调节亮度 (仅开/关)", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        // Note: This status text might not reflect Service state perfectly if app is reopened
        if (isFlashlightOn || isMotionMode) { 
             // Simple hint. 
             // If Motion Mode is on, the light might be off temporarily (waiting for motion).
             // So "Lighting is on" text should rely on actual light state if possible, 
             // but we don't have a callback from CameraManager here easily.
             // We rely on 'isFlashlightOn' which tracks local state. Service state is unknown.
        }
    }
}

@Composable
fun ModeCard(
    title: String,
    desc: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    content: @Composable () -> Unit = {}
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if(checked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(desc, style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = checked, onCheckedChange = onCheckedChange)
            }
            content()
        }
    }
}
