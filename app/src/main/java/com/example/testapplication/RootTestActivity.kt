package com.example.testapplication

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RootTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RootTestView()
        }
    }
}

@Composable
fun RootTestView() {
    var command by remember { mutableStateOf("ls -l /data/data") }
    var output by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    var isRooted by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            isRooted = RootUtils.isRooted()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .padding(top = 30.dp)
    ) {
        Text(text = "Root Status: ${if (isRooted) "Granted" else "Not Granted"}")
        
        Spacer(modifier = Modifier.height(16.dp))
        
        TextField(
            value = command,
            onValueChange = { command = it },
            label = { Text("Command") },
            modifier = Modifier.fillMaxWidth()
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Button(
            onClick = {
                scope.launch(Dispatchers.IO) {
                    val result = RootUtils.execRootCmd(command)
                    withContext(Dispatchers.Main) {
                        output = result
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Execute as Root")
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(text = "Output:")
        Text(
            text = output,
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
                .verticalScroll(rememberScrollState())
        )
    }
}
