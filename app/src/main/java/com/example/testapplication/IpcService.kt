package com.example.testapplication

import android.app.Service
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.HashSet
import kotlin.concurrent.thread

class IpcService : Service() {

    var set= HashSet<String>()
    var num = 0
    override fun onCreate() {
        super.onCreate()
        CoroutineScope(Dispatchers.IO).launch {
            delay(2000)
            num++
            println("IpcService-oncreate${num}")
        }
        set.minus("")
    }

    override fun onBind(intent: Intent?): IBinder? {
        return  object : ICalcService.Stub() {
            override fun add(a: Int, b: Int): Int {
                return a + b + (a * b)
            }

            override fun minue(a: Int, b: Int): Int {
               return a-b
            }

            override fun mutilp(a: Int): Int {
                return a
            }

            override fun setName(): Int {
                return 3
            }

            override fun getName(): String? {
                return "2333"
            }
        }
    }
}