package com.example.testapplication

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.testapplication.ui.theme.TestApplicationTheme

class MainActivity : ComponentActivity() {
    lateinit var test1: Test1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        test1 = Test1()
        enableEdgeToEdge()
        setContent {
            TestApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Greeting()
                }
            }
        }
        


    }

    @Composable
    fun Greeting() {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Text(
                text = "协程调用",
                modifier = Modifier.padding(10.dp).clickable {
                    // 运行原有的测试
                    test1.funTest2()
                }
            )
        }
    }
}

