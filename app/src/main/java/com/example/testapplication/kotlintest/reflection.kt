package com.example.testapplication.kotlintest

class reflection {
}


fun main(){
    var list= ArrayList<Student>()
    var listA= ArrayList<Man>()
    listA.addAll(list)

//    listA=list
}
//fun getData  (data:out T){
//
//}
open class Man{}
open class Student: Man(){}
open class Teacher: Man(){}
class XiaoMing : Student(){}
class XiaoLi : Student(){}
class LaoWang : Teacher(){}