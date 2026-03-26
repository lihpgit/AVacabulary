package com.example.testapplication.vocab

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

class WordListActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { WordListScreen() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WordListScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { WordRepository.getInstance(context) }

    val loadState by repository.loadState.collectAsState()
    val overrides by repository.overrides.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }

    // Trigger load on first open
    LaunchedEffect(Unit) {
        if (loadState is LoadState.Idle) repository.loadWords()
    }

    val allWords = (loadState as? LoadState.Success)?.words ?: emptyList()
    val unmastered = remember(allWords, overrides) {
        allWords.filter { !repository.effectiveMastered(it.topicId, it.masteredInDb, overrides) }
    }
    val mastered = remember(allWords, overrides) {
        allWords.filter { repository.effectiveMastered(it.topicId, it.masteredInDb, overrides) }
    }
    val baseList = if (selectedTab == 0) unmastered else mastered
    val displayList = remember(baseList, searchQuery) {
        if (searchQuery.isBlank()) baseList
        else baseList.filter {
            it.word.contains(searchQuery, ignoreCase = true) ||
                    it.meanCn.contains(searchQuery)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("背单词") },
                navigationIcon = {
                    IconButton(onClick = { (context as? WordListActivity)?.finish() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        scope.launch {
                            repository.loadWords()
                        }
                    }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            // Search bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("搜索单词或释义...") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            )

            // Tabs
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0; searchQuery = "" },
                    text = { Text("未斩（${unmastered.size}）") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1; searchQuery = "" },
                    text = { Text("已斩（${mastered.size}）") }
                )
            }

            when (val state = loadState) {
                is LoadState.Idle, is LoadState.Loading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(12.dp))
                            Text("正在读取百词斩数据库…")
                        }
                    }
                }

                is LoadState.Error -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(24.dp)
                        ) {
                            Text(
                                "加载失败",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(state.message, style = MaterialTheme.typography.bodySmall)
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(onClick = { scope.launch { repository.loadWords() } }) {
                                Text("重试")
                            }
                        }
                    }
                }

                is LoadState.Success -> {
                    if (displayList.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(if (searchQuery.isBlank()) "暂无单词" else "未找到匹配单词")
                        }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            itemsIndexed(displayList) { _, word ->
                                // Find the index in the full base list for FlashCard start position
                                val indexInBase = baseList.indexOf(word)
                                WordListItem(word = word, onClick = {
                                    val intent = Intent(context, FlashCardActivity::class.java).apply {
                                        putExtra(FlashCardActivity.EXTRA_FILTER, if (selectedTab == 0) "unmastered" else "mastered")
                                        putExtra(FlashCardActivity.EXTRA_START_INDEX, indexInBase.coerceAtLeast(0))
                                    }
                                    context.startActivity(intent)
                                })
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WordListItem(word: Word, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = word.word,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
            modifier = Modifier.width(140.dp)
        )
        Text(
            text = word.meanCn,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            maxLines = 2
        )
    }
}
