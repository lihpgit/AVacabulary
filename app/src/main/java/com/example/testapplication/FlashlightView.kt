package com.example.testapplication

import android.content.Context
import android.hardware.camera2.CameraManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun FlashlightView() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Button(onClick = {
            scope.launch {
                try {
                    val cameraId = cameraManager.cameraIdList[0] // 通常后置摄像头是 "0"
                    repeat(3) {
                        // 开启
                        cameraManager.setTorchMode(cameraId, true)
                        delay(1000)
                        // 关闭
                        cameraManager.setTorchMode(cameraId, false)
                        delay(1000)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }) {
            Text("闪烁闪光灯 3 次")
        }
    }
}
