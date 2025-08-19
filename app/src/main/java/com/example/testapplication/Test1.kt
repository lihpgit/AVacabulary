package com.example.testapplication

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random


class Test1 {

    fun funTest2() {
        println("ceshi***0---${Thread.currentThread().name}")
        CoroutineScope(Dispatchers.IO).launch {
            val count = 19 * Random.nextInt(17)
            delay(10)
            println("ceshi***1-${count}--${Thread.currentThread().name}")
            val num = withContext(Dispatchers.Main) {
                delay(500)
                println("ceshi***2-${count}--${Thread.currentThread().name}")
                5
            }
            

            val data =async {
//                delay(2000)
                println("ceshi***3-${count}--${Thread.currentThread().name}")
                40
            }

            val result=async {
//                delay(3000)
                println("ceshi***4-${count}--${Thread.currentThread().name}")
                3
            }

            println("ceshi***5-${num}--${data.await()+result.await()}--${Thread.currentThread().name}")
        }
        println("fsdf--${Thread.currentThread().name}")
    }

    fun test1(call: suspend List<String>.() -> Unit) {
        val list = List<String>(6) { b ->
            "dfsdf${b}"
        }
        CoroutineScope(Dispatchers.IO).launch {
            delay(1000)
            call(list)
        }
    }
}