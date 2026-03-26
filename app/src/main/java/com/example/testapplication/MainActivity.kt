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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.testapplication.tetris.TetrisActivity
import com.example.testapplication.vocab.WordListActivity

@OptIn(ExperimentalLayoutApi::class)
class MainActivity : ComponentActivity() {
    val test1 by lazy { Test1() }
    var binder: ICalcService? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // test1 访问时才会初始化
        enableEdgeToEdge()
        setContent {
            Column(modifier = Modifier.fillMaxSize().padding(top = 48.dp)) {
                SimpleComposeTest()

                FlowRow(modifier = Modifier.padding(8.dp)) {
                    // 服务端启动
                    Button(
                        onClick = {
                            val intent = Intent(this@MainActivity, IpcService::class.java)
                            this@MainActivity.bindService(intent, object : ServiceConnection {
                                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                                    binder = ICalcService.Stub.asInterface(service)
                                    println("服务端绑定成功")
                                }
                                override fun onServiceDisconnected(name: ComponentName?) {
                                    println("服务端绑定失败")
                                }
                            }, Context.BIND_AUTO_CREATE)
                        },
                        modifier = Modifier.padding(4.dp)
                    ) {
                        Text("绑定服务")
                    }

                    // 客户端调用
                    Button(
                        onClick = { println("调用返回${binder?.add(2, 5)}") },
                        modifier = Modifier.padding(4.dp)
                    ) {
                        Text("调用AIDL")
                    }

                    // 噪音测试（独立页面）
                    Button(
                        onClick = { startActivity(Intent(this@MainActivity, NoiseActivity::class.java)) },
                        modifier = Modifier.padding(4.dp)
                    ) {
                        Text("去噪音页")
                    }

                    // 闪光灯
                    Button(
                        onClick = { startActivity(Intent(this@MainActivity, FlashlightActivity::class.java)) },
                        modifier = Modifier.padding(4.dp)
                    ) {
                        Text("去闪光灯")
                    }

                    // 俄罗斯方块
                    Button(
                        onClick = { startActivity(Intent(this@MainActivity, TetrisActivity::class.java)) },
                        modifier = Modifier.padding(4.dp)
                    ) {
                        Text("去俄罗斯方块")
                    }

                    // Root测试
                    Button(
                        onClick = { startActivity(Intent(this@MainActivity, RootTestActivity::class.java)) },
                        modifier = Modifier.padding(4.dp)
                    ) {
                        Text("去Root测试")
                    }

                    // 数据库查看器
                    Button(
                        onClick = { startActivity(Intent(this@MainActivity, DatabaseViewerActivity::class.java)) },
                        modifier = Modifier.padding(4.dp)
                    ) {
                        Text("去查数据库")
                    }

                    Button(
                        onClick = { startActivity(Intent(this@MainActivity, ClipboardMonitorActivity::class.java)) },
                        modifier = Modifier.padding(4.dp)
                    ) {
                        Text("去监控剪贴板")
                    }

                    Button(
                        onClick = { startActivity(Intent(this@MainActivity, LightSensorActivity::class.java)) },
                        modifier = Modifier.padding(4.dp)
                    ) {
                        Text("去测光感")
                    }

                    Button(
                        onClick = { startActivity(Intent(this@MainActivity, IrRemoteActivity::class.java)) },
                        modifier = Modifier.padding(4.dp)
                    ) {
                        Text("红外遥控")
                    }

                    Button(
                        onClick = { startActivity(Intent(this@MainActivity, NetworkSpeedActivity::class.java)) },
                        modifier = Modifier.padding(4.dp)
                    ) {
                        Text("网络测速")
                    }

                    Button(
                        onClick = { startActivity(Intent(this@MainActivity, WordListActivity::class.java)) },
                        modifier = Modifier.padding(4.dp)
                    ) {
                        Text("背单词")
                    }
                }
            }
        }
    }
}
