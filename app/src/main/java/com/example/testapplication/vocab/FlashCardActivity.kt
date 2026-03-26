package com.example.testapplication.vocab

import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

class FlashCardActivity : ComponentActivity() {
    companion object {
        const val EXTRA_FILTER      = "filter_type"
        const val EXTRA_START_INDEX = "start_index"
        const val EXTRA_BOOK_ID     = "book_id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val filterType = intent.getStringExtra(EXTRA_FILTER) ?: "unmastered"
        val startIndex = intent.getIntExtra(EXTRA_START_INDEX, 0)
        val bookId     = intent.getIntExtra(EXTRA_BOOK_ID, WordBook.GAOKAO.id)
        setContent { FlashCardScreen(filterType, startIndex, bookId) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlashCardScreen(filterType: String, startIndex: Int, bookId: Int) {
    val context = LocalContext.current
    val repository = remember { WordRepository.getInstance(context) }
    val overrides by repository.overrides.collectAsState()

    // 进入刷词时固定本次词单（不随后续 overrides 变化而改变顺序/数量）
    val words = remember {
        val state = repository.loadState.value
        if (state is LoadState.Success) {
            val ov = repository.overrides.value
            when (filterType) {
                "mastered" -> state.words.filter { repository.effectiveMastered(it.topicId, it.masteredInDb, ov) }
                else -> state.words.filter { !repository.effectiveMastered(it.topicId, it.masteredInDb, ov) }
            }
        } else emptyList()
    }

    var currentIndex by remember { mutableIntStateOf(startIndex.coerceIn(0, (words.size - 1).coerceAtLeast(0))) }
    var isFlipped by remember { mutableStateOf(false) }

    // TTS
    val ttsRef = remember { mutableStateOf<TextToSpeech?>(null) }
    var ttsReady by remember { mutableStateOf(false) }
    var speechRate by remember { mutableFloatStateOf(1.0f) }
    var autoRead by remember { mutableStateOf(false) }

    DisposableEffect(context) {
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                engine?.language = Locale.US
                ttsRef.value = engine
                ttsReady = true
            }
        }
        onDispose {
            engine?.shutdown()
            ttsRef.value = null
        }
    }

    fun speak(text: String) {
        ttsRef.value?.apply {
            setSpeechRate(speechRate)
            speak(text, TextToSpeech.QUEUE_FLUSH, null, "word")
        }
    }

    // Auto-read on new word
    val currentWord = words.getOrNull(currentIndex)
    LaunchedEffect(currentIndex) {
        if (autoRead && currentWord != null) speak(currentWord.word)
    }

    fun goNext() {
        if (currentIndex < words.size - 1) {
            currentIndex++
            isFlipped = false
        }
    }

    fun goPrev() {
        if (currentIndex > 0) {
            currentIndex--
            isFlipped = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (words.isEmpty()) "无单词" else "${currentIndex + 1} / ${words.size}",
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { (context as? FlashCardActivity)?.finish() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // Auto-read toggle
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("自动朗读", style = MaterialTheme.typography.bodySmall)
                        Switch(
                            checked = autoRead,
                            onCheckedChange = { autoRead = it },
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }
                }
            )
        }
    ) { padding ->
        if (words.isEmpty()) {
            Box(modifier = Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("没有单词，请返回重新选择")
            }
            return@Scaffold
        }

        val word = currentWord!!
        val effectiveMastered = repository.effectiveMastered(word.topicId, word.masteredInDb, overrides)

        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // Speed selector
            SpeedSelector(selectedRate = speechRate, onSelect = { speechRate = it })

            Spacer(modifier = Modifier.height(12.dp))

            // Flip card
            FlipCard(
                word = word,
                isFlipped = isFlipped,
                onFlip = { isFlipped = !isFlipped },
                onSpeak = { speak(word.word) },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Mastered toggle
            val toggleLabel = if (effectiveMastered) "已斩 ✓（点击取消）" else "未斩（点击标为已斩）"
            val toggleColors = if (effectiveMastered)
                ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
            else
                ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)

            Button(
                onClick = { repository.toggleMastered(word.topicId, effectiveMastered) },
                colors = toggleColors,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(toggleLabel)
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Navigation
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = ::goPrev,
                    enabled = currentIndex > 0,
                    modifier = Modifier.weight(1f)
                ) { Text("上一个") }

                OutlinedButton(
                    onClick = ::goNext,
                    enabled = currentIndex < words.size - 1,
                    modifier = Modifier.weight(1f)
                ) { Text("下一个") }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun FlipCard(
    word: Word,
    isFlipped: Boolean,
    onFlip: () -> Unit,
    onSpeak: () -> Unit,
    modifier: Modifier = Modifier
) {
    val rotation by animateFloatAsState(
        targetValue = if (isFlipped) 180f else 0f,
        animationSpec = tween(durationMillis = 400),
        label = "cardFlip"
    )

    Card(
        modifier = modifier.graphicsLayer {
            rotationY = rotation
            cameraDistance = 10f * density
        },
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onFlip)
        ) {
            if (rotation <= 90f) {
                // Front face
                CardFront(word = word, onSpeak = onSpeak)
            } else {
                // Back face — mirror to counteract the parent rotation
                Box(modifier = Modifier.fillMaxSize().graphicsLayer { rotationY = 180f }) {
                    CardBack(word = word, onSpeak = onSpeak)
                }
            }
        }
    }
}

@Composable
private fun CardFront(word: Word, onSpeak: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = word.word,
            fontSize = 42.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        if (word.accent.isNotBlank()) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = word.accent,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
        Spacer(modifier = Modifier.height(20.dp))
        OutlinedButton(onClick = onSpeak) {
            Text("♪ 朗读", fontSize = 16.sp)
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "点击卡片翻转查看释义",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
    }
}

@Composable
private fun CardBack(word: Word, onSpeak: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Text(
                text = word.word,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(onClick = onSpeak) {
                Text("♪", fontSize = 20.sp, color = MaterialTheme.colorScheme.primary)
            }
        }
        if (word.accent.isNotBlank()) {
            Text(
                text = word.accent,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
        Text(
            text = word.meanCn,
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.primary
        )
        if (word.sentence.isNotBlank()) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = word.sentence,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (word.sentenceTrans.isNotBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = word.sentenceTrans,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

private val speedOptions = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f)
private val speedLabels = listOf("0.5x", "0.75x", "1x", "1.25x", "1.5x")

@Composable
private fun SpeedSelector(selectedRate: Float, onSelect: (Float) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("速度：", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(end = 4.dp))
        speedOptions.forEachIndexed { i, rate ->
            val selected = selectedRate == rate
            FilterChip(
                selected = selected,
                onClick = { onSelect(rate) },
                label = { Text(speedLabels[i], fontSize = 12.sp) },
                modifier = Modifier.padding(horizontal = 2.dp)
            )
        }
    }
}
