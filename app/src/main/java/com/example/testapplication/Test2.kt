package com.example.testapplication

fun main(){
    val a = Vec2(1.0, 2.0)
    val b = Vec2(0.5, 0.5)
    val c = a + b          // plus
    val d = -a             // unaryMinus
    var e = a
    e++                    // inc
    val f = a * 2.0        // times
    println(c)
    println(f)

}
fun Any?.noNull(): Boolean{
    return this!=null
}

fun  String?.value(): String{
    if (this==null){
        return ""
    }else{
        return this
    }
}
fun Test1.extentionTest(){
    println("kuozhanhanshu")
}

data class Vec2(val x: Double, val y: Double) {
    operator fun plus(o: Vec2) = Vec2(x + o.x, y + o.y)
    operator fun times(k: Double) = Vec2(x * k, y * k)
    operator fun unaryMinus() = Vec2(-x, -y)
    operator fun inc() = Vec2(x + 1, y + 1) // ++v 或 v++
}