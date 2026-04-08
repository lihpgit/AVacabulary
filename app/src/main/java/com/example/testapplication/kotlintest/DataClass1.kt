package com.example.testapplication.kotlintest

data  class DataClass1(val age: Int,var name: String) {
}

fun main(){
//    println("valueis ${findSubStr()}")

}
fun f157(){
    val list=arrayOf("This", "is", "an", "example", "of", "text", "justification.")
    var left=0
    var rigit=0

}
fun findSubStr(): Int{
    var haystack = "dfdsadbutsad"
    var needle = "sad"
    var low=0
    var start=-1
    var isloop=true

    while (isloop){
        if (low+needle.length>=haystack.length){
            isloop=false
            break
        }
        if (haystack[low] == needle[0]){
            for (i in 0 until needle.length){
                if (haystack[low+i] != needle[i]){
                    low+=i
                    break
                }
                if (i==needle.length-1){
                    start=low
                    isloop=false
                    break
                }
            }
        }else{
            low++
        }

    }
    return start
}