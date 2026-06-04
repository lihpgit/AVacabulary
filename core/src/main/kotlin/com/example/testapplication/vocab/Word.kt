package com.example.testapplication.vocab

data class Word(
    val topicId: Int,
    val word: String,
    val accent: String,
    val meanCn: String,
    val sentence: String,
    val sentenceTrans: String,
    val masteredInDb: Boolean   // true = 已斩 (topic_obn < 1)
)
