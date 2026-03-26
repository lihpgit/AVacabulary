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

    val loadState  by repository.loadState.collectAsState()
    val syncState  by repository.syncState.collectAsState()
    val overrides  by repository.overrides.collectAsState()

    var selectedBook by remember { mutableStateOf(WordBook.GAOKAO) }
    var selectedTab  by remember { mutableIntStateOf(0) }
    var searchQuery  by remember { mutableStateOf("") }
    var showSyncDialog by remember { mutableStateOf(false) }

    // 加载词书（切换词书时重新加载）
    LaunchedEffect(selectedBook) {
        repository.loadWords(selectedBook)
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
            it.word.contains(searchQuery, ignoreCase = true) || it.meanCn.contains(searchQuery)
        }
    }

    // ── 同步确认对话框 ──────────────────────────────────────────
    if (showSyncDialog) {
        AlertDialog(
            onDismissRequest = { showSyncDialog = false },
            title = { Text("重新加载内置词库") },
            text  = { Text("将把 assets 目录内的数据库文件重新覆盖到本地存储，适用于你更新了 assets 内的 DB 文件并重新安装 App 后。\n\n你手动调整过的已斩/未斩状态不受影响。确认继续？") },
            confirmButton = {
                TextButton(onClick = {
                    showSyncDialog = false
                    scope.launch { repository.syncFromAssets() }
                }) { Text("确认") }
            },
            dismissButton = {
                TextButton(onClick = { showSyncDialog = false }) { Text("取消") }
            }
        )
    }

    // 同步结果 Snackbar
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(syncState) {
        when (val s = syncState) {
            is SyncState.Success -> {
                repository.loadWords(selectedBook, forceReload = true)
                snackbarHostState.showSnackbar("同步成功，已重新加载")
                repository.resetSyncState()
            }
            is SyncState.Error -> {
                snackbarHostState.showSnackbar("同步失败：${s.message}")
                repository.resetSyncState()
            }
            else -> {}
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("背单词") },
                navigationIcon = {
                    IconButton(onClick = { (context as? WordListActivity)?.finish() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (syncState is SyncState.Syncing) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(32.dp)
                                .padding(end = 8.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        TextButton(onClick = { showSyncDialog = true }) {
                            Text("重载词库")
                        }
                    }
                    IconButton(onClick = {
                        scope.launch { repository.loadWords(selectedBook, forceReload = true) }
                    }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "重新加载")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {

            // ── 词书选择 ────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                WordBook.entries.forEach { book ->
                    FilterChip(
                        selected = selectedBook == book,
                        onClick  = {
                            if (selectedBook != book) {
                                selectedBook = book
                                selectedTab  = 0
                                searchQuery  = ""
                            }
                        },
                        label = { Text(book.displayName) }
                    )
                }
            }

            // ── 搜索框 ──────────────────────────────────────────
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("搜索单词或释义…") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 2.dp)
            )

            // ── 未斩 / 已斩 Tab ─────────────────────────────────
            TabRow(selectedTabIndex = selectedTab) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0; searchQuery = "" },
                    text = { Text("未斩（${unmastered.size}）") })
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1; searchQuery = "" },
                    text = { Text("已斩（${mastered.size}）") })
            }

            // ── 内容区 ──────────────────────────────────────────
            when (val state = loadState) {
                is LoadState.Idle, is LoadState.Loading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(12.dp))
                            Text("正在读取 ${selectedBook.displayName} 词书…")
                        }
                    }
                }
                is LoadState.Error -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(24.dp)
                        ) {
                            Text("加载失败", style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.height(8.dp))
                            Text(state.message, style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = { scope.launch { repository.loadWords(selectedBook) } }) {
                                Text("重试")
                            }
                        }
                    }
                }
                is LoadState.Success -> {
                    if (displayList.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(if (searchQuery.isBlank()) "暂无单词" else "未找到匹配单词")
                        }
                    } else {
                        LazyColumn(Modifier.fillMaxSize()) {
                            itemsIndexed(displayList) { _, word ->
                                val indexInBase = baseList.indexOf(word)
                                WordListItem(word = word, onClick = {
                                    context.startActivity(
                                        Intent(context, FlashCardActivity::class.java).apply {
                                            putExtra(FlashCardActivity.EXTRA_FILTER,
                                                if (selectedTab == 0) "unmastered" else "mastered")
                                            putExtra(FlashCardActivity.EXTRA_START_INDEX,
                                                indexInBase.coerceAtLeast(0))
                                            putExtra(FlashCardActivity.EXTRA_BOOK_ID, selectedBook.id)
                                        }
                                    )
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
