package com.example.testapplication

import android.app.Application
import com.tencent.mmkv.MMKV

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        MMKV.initialize(this)
    }
}
