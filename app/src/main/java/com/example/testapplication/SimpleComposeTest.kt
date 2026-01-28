package com.example.testapplication

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import kotlin.random.Random

/**
 * 简单的Compose测试
 * 请尝试点击以下组件名称查看源码：
 * - Column
 * - Text  
 * - Button
 * - MaterialTheme
 */
@Composable
fun SimpleComposeTest() {
    val isTestPreview= LocalInspectionMode.current
    var lhpTest by remember{ mutableStateOf("****###ll") }
    LaunchedEffect(Unit) {
        lhpTest= Random.nextInt().toString()
    }
    Column(
        modifier = Modifier
            .wrapContentSize()
            .padding(16.dp)
    ) {
        if (!isTestPreview){
            Text(
                text = "测试源码查看功能${lhpTest}",
                style = MaterialTheme.typography.headlineMedium
            )

        }
        Button(
            onClick = { }
        ) {
            Text("点击我")
        }
    }
}
