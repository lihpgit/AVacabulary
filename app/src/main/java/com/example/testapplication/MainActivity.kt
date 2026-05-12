package com.example.testapplication

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.example.testapplication.vocab.WordListActivity

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, WordListActivity::class.java))
        finish()
    }
}
