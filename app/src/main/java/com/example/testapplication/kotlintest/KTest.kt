package com.example.testapplication.kotlintest

class KTest {

   inline fun fuction1(funName:String,crossinline block:(String)-> Unit){
        println("function${funName}1")
        block("fdddddd${funName}")
        println("function${funName}2")
        println("function${funName}3")

    }

    fun function2(){
        println("function21")
        fuction1("call0") call@{
            fuction1("call1") call1@{
                fuction1("call2") call2@{
                    return@call2
                }
            }
        }
        println("function22")
        println("function22")
    }
}

fun  main(){
    KTest().function2()
}