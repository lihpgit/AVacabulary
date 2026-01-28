package com.example.testapplication.kotlintest;

import java.util.LinkedList;
import java.util.List;
import java.util.Objects;

public class Reflection {
    List<String> list=new LinkedList<String>();
    List<Object> listO=new LinkedList<Object>();
    List<Object> list1=new LinkedList< >();
    public void add(){
        listO.addAll(list);
        listO=list1;
    }
    public static void main(String[] args){

    }
}
