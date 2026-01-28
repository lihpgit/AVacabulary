package com.example.testapplication

import android.os.Bundle
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.ContentInfoCompat
import androidx.core.view.OnReceiveContentListener
import androidx.core.view.ViewCompat
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

class ClipboardMonitorActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ClipboardMonitorView()
        }
    }
}

@Composable
fun ClipboardMonitorView() {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Compose 版", style = MaterialTheme.typography.titleLarge)
        ComposeClipboardMonitor()
        
        Spacer(modifier = Modifier.height(32.dp))
        
        Text("Android View 版 (嵌入)", style = MaterialTheme.typography.titleLarge)
        AndroidViewClipboardMonitor()
    }
}

@Composable
fun ComposeClipboardMonitor() {
    val context = LocalContext.current
    // 监听粘贴：通过自定义 TextFieldValue 的变动来推测（Compose 没有直接的 OnPasteListener）
    // 或者监听 ClipboardManager 的变化
    
    var text by remember { mutableStateOf(TextFieldValue("")) }
    var log by remember { mutableStateOf("日志: ") }
    
    // 1. 监听复制：使用 LocalClipboardManager
    // 注意：Compose 的 SelectionContainer 默认处理了复制，但要监听 "复制事件" 比较难，
    // 通常是自定义 Action 或者监听系统剪贴板变化
    val clipboardManager = LocalClipboardManager.current
    
    // 简单的文本输入框
    OutlinedTextField(
        value = text,
        onValueChange = { newValue ->
            // 简单的粘贴检测逻辑：一次性增加大量字符
            if (newValue.text.length - text.text.length > 5) {
                log += "\n检测到疑似粘贴行为 (Compose)"
            }
            text = newValue
        },
        label = { Text("尝试在此输入或粘贴") },
        modifier = Modifier.fillMaxWidth()
    )
    
    Button(
        onClick = {
            clipboardManager.setText(AnnotatedString(text.text))
            log += "\n用户点击了复制按钮 (Compose)"
        },
        modifier = Modifier.padding(top = 8.dp)
    ) {
        Text("复制内容到剪贴板")
    }
    
    Text(log, modifier = Modifier.padding(top = 8.dp))
}

@Composable
fun AndroidViewClipboardMonitor() {
    val context = LocalContext.current
    var logText by remember { mutableStateOf("日志: ") }

    Column {
        AndroidView(
            factory = { ctx ->
                val editText = EditText(ctx).apply {
                    hint = "Android View EditText"
                }
                
                // 1. 监听粘贴：使用 ViewCompat 统一处理 (兼容 API 12+)
                ViewCompat.setOnReceiveContentListener(editText, arrayOf("text/plain"), object : OnReceiveContentListener {
                    override fun onReceiveContent(view: android.view.View, payload: ContentInfoCompat): ContentInfoCompat? {
                        logText += "\n检测到粘贴/拖入 (ContentInfoCompat)"
                        return payload // 返回 payload 让系统继续处理（上屏）
                    }
                })
                
                // 2. 监听复制：设置 CustomSelectionActionModeCallback
                editText.customSelectionActionModeCallback = object : android.view.ActionMode.Callback {
                    override fun onCreateActionMode(mode: android.view.ActionMode?, menu: android.view.Menu?): Boolean = true
                    override fun onPrepareActionMode(mode: android.view.ActionMode?, menu: android.view.Menu?): Boolean = true
                    override fun onActionItemClicked(mode: android.view.ActionMode?, item: android.view.MenuItem?): Boolean {
                        if (item?.itemId == android.R.id.copy) {
                            logText += "\n检测到复制操作 (Android View)"
                        }
                        if (item?.itemId == android.R.id.paste) {
                            logText += "\n检测到粘贴操作 (Android View Menu)"
                        }
                        return false // 返回 false 让系统继续处理
                    }
                    override fun onDestroyActionMode(mode: android.view.ActionMode?) {}
                }
                
                editText
            },
            modifier = Modifier.fillMaxWidth().height(100.dp)
        )
        
        Text(logText)
    }
}
