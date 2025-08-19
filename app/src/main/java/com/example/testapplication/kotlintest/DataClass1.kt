package com.example.testapplication.kotlintest

data  class DataClass1(val age: Int,var name: String) {
}

fun main(){
    val obj= DataClass1(3434,"jack")
    val obj1=obj.copy(name = "ddsd")

    println("${obj.hashCode()}----${obj1.hashCode()}")

    var (age,name)=obj
    println("${age}--${name}")
}