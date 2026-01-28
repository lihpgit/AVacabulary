package com.example.testapplication

import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileReader

@OptIn(ExperimentalMaterial3Api::class)
class DatabaseViewerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DatabaseViewerView()
        }
    }
}

enum class ViewerState {
    HOME, TABLE_LIST, DATA_TABLE, TEXT_VIEW
}

data class SearchResult(
    val type: String, 
    val path: String, 
    val subName: String, 
    val snippet: String
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DatabaseViewerView() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    // 默认包名路径
    var packageName by remember { mutableStateOf("com.jiongji.andriod.card") }
    
    var currentState by remember { mutableStateOf(ViewerState.HOME) }
    var globalSearchResults by remember { mutableStateOf<List<SearchResult>?>(null) }
    var searchKeywords by remember { mutableStateOf("akin") } // 默认搜这个核心词
    var isSearching by remember { mutableStateOf(false) }
    var searchProgress by remember { mutableStateOf("") }
    var includeSdCard by remember { mutableStateOf(true) }

    // 详情页数据
    var currentDbName by remember { mutableStateOf("") }
    var currentTableName by remember { mutableStateOf("") }
    var currentTableData by remember { mutableStateOf<List<Map<String, String>>>(emptyList()) }
    var currentTableHeaders by remember { mutableStateOf<List<String>>(emptyList()) }
    var currentTextTitle by remember { mutableStateOf("") }
    var currentTextContent by remember { mutableStateOf("") }

    // 核心：调用系统 grep 进行内核级暴力搜索
    fun performGlobalSearch() {
        val keyword = searchKeywords.trim()
        if (keyword.isEmpty()) return

        scope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                isSearching = true
                searchProgress = "正在进行全盘 grep 暴力搜索..."
                globalSearchResults = emptyList()
            }

            // 构造搜索路径
            val paths = mutableListOf<String>()
            paths.add("/data/data/$packageName")
            if (includeSdCard) {
                paths.add("/sdcard/Android/data/$packageName")
                // 有些应用可能直接在根目录建文件夹，暂时只搜标准路径
            }

            // 构造 grep 命令
            // -r: 递归
            // -n: 显示行号
            // -i: 忽略大小写
            // -a: 强制处理二进制文件为文本 (关键！)
            val pathArgs = paths.joinToString(" ")
            val cmd = "grep -rnia \"$keyword\" $pathArgs"

            try {
                // 执行 grep
                // 注意：grep 可能因为权限问题报错，或者文件太多比较慢
                // 这里的 execRootCmd 需要支持长输出
                val rawOutput = RootUtils.execRootCmd(cmd)
                
                val results = mutableListOf<SearchResult>()
                val lines = rawOutput.split("\n")
                
                for (line in lines) {
                    if (line.isBlank()) continue
                    // grep 输出格式: /path/to/file:line_num:matched_content
                    // 注意：路径里可能包含冒号，内容里也可能包含
                    // 简单的解析逻辑：
                    val parts = line.split(":", limit = 3)
                    if (parts.size >= 3) {
                        val path = parts[0].trim()
                        val lineNum = parts[1]
                        val content = parts[2].trim()
                        
                        // 过滤掉我们不关心的类型
                        if (path.contains("/lib/") || path.contains("/cache/")) continue
                        
                        val type = if (path.endsWith(".db")) "DB" else "FILE"
                        // 限制 snippet 长度
                        val snippetDisplay = if (content.length > 100) content.take(100) + "..." else content
                        
                        // 如果是 DB，稍后可以尝试解析表名（可选优化），这里先作为 FILE 列出
                        // 为了避免重复文件刷屏（grep 每个匹配行都会输出），这里简单去重逻辑：
                        // 同一个文件只显示前 3 个匹配
                        val count = results.count { it.path == path }
                        if (count < 3) {
                            results.add(SearchResult(type, path, "", "Line $lineNum: $snippetDisplay"))
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    isSearching = false
                    searchProgress = ""
                    globalSearchResults = results
                    if (results.isEmpty()) {
                        Toast.makeText(context, "全盘搜索未找到，请确认包名或关键词", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isSearching = false
                    searchProgress = "搜索出错: ${e.message}"
                }
            }
        }
    }

    // 打开文本文件逻辑 (提前定义以供 openItem 调用)
    fun openAsText(item: SearchResult) {
        scope.launch(Dispatchers.IO) {
            val localFile = File(context.cacheDir, "temp_view.txt")
            RootUtils.execRootCmd("cp \"${item.path}\" ${localFile.absolutePath}")
            RootUtils.execRootCmd("chmod 666 ${localFile.absolutePath}")
            val content = try { 
                 RootUtils.execRootCmd("head -c 10000 \"${item.path}\"")
            } catch (e: Exception) { "Read Error: ${e.message}" }
            
            withContext(Dispatchers.Main) {
                currentTextTitle = File(item.path).name
                currentTextContent = content
                currentState = ViewerState.TEXT_VIEW
            }
        }
    }

    // 打开文件逻辑
    fun openItem(item: SearchResult) {
        if (item.type == "DB") {
            // 尝试作为数据库打开
            scope.launch(Dispatchers.IO) {
                val localFile = File(context.cacheDir, "temp_view.db")
                RootUtils.execRootCmd("cp \"${item.path}\" ${localFile.absolutePath}")
                RootUtils.execRootCmd("chmod 666 ${localFile.absolutePath}")
                
                try {
                    val db = SQLiteDatabase.openDatabase(localFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
                    // 自动寻找包含 keyword 的表
                    val cursorTables = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null)
                    val tables = mutableListOf<String>()
                    while (cursorTables.moveToNext()) tables.add(cursorTables.getString(0))
                    cursorTables.close()
                    
                    var targetTable = ""
                    var targetData = emptyList<Map<String, String>>()
                    var targetHeaders = emptyList<String>()

                    // 遍历所有表找数据（只找第一个匹配的表展示）
                    for (table in tables) {
                        try {
                            // 简化的全表搜索
                            val c = db.rawQuery("SELECT * FROM $table", null)
                            val headers = c.columnNames.toList()
                            val data = mutableListOf<Map<String, String>>()
                            var foundInTable = false
                            
                            // 只读前 100 行看看有没有
                            var count = 0
                            while (c.moveToNext() && count < 100) {
                                count++
                                val row = mutableMapOf<String, String>()
                                var rowHasKey = false
                                for (col in headers) {
                                    val valStr = c.getString(c.getColumnIndex(col)) ?: ""
                                    row[col] = valStr
                                    if (valStr.contains(searchKeywords, true)) rowHasKey = true
                                }
                                data.add(row)
                                if (rowHasKey) foundInTable = true
                            }
                            c.close()
                            
                            if (foundInTable) {
                                targetTable = table
                                targetData = data
                                targetHeaders = headers
                                break
                            }
                        } catch (e: Exception) {}
                    }
                    db.close()

                    withContext(Dispatchers.Main) {
                        if (targetTable.isNotEmpty()) {
                            currentDbName = File(item.path).name
                            currentTableName = targetTable
                            currentTableHeaders = targetHeaders
                            currentTableData = targetData
                            currentState = ViewerState.DATA_TABLE
                        } else {
                            // 没找到表，转文本打开
                            Toast.makeText(context, "DB打开成功但未匹配到表，尝试文本模式", Toast.LENGTH_SHORT).show()
                            openAsText(item)
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { openAsText(item) }
                }
            }
        } else {
            openAsText(item)
        }
    }

    // openAsText 已移动到 openItem 之前

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        text = when(currentState) {
                            ViewerState.HOME -> "终极暴力搜索"
                            ViewerState.DATA_TABLE -> "$currentDbName / $currentTableName"
                            ViewerState.TEXT_VIEW -> currentTextTitle
                            else -> "详情"
                        },
                        style = MaterialTheme.typography.titleMedium
                    ) 
                },
                navigationIcon = {
                    if (currentState != ViewerState.HOME) {
                        IconButton(onClick = { currentState = ViewerState.HOME }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (currentState) {
                ViewerState.HOME -> HomeView(
                    packageName = packageName, onPkgChange = { packageName = it },
                    keywords = searchKeywords, onKeywordsChange = { searchKeywords = it },
                    isSearching = isSearching, progress = searchProgress,
                    includeSdCard = includeSdCard, onIncludeSdCardChange = { includeSdCard = it },
                    searchResults = globalSearchResults,
                    onSearch = ::performGlobalSearch,
                    onOpenItem = ::openItem,
                    onClearSearch = { globalSearchResults = null }
                )
                ViewerState.DATA_TABLE -> DataTableView(currentTableHeaders, currentTableData, searchKeywords)
                ViewerState.TEXT_VIEW -> TextView(currentTextContent, searchKeywords)
                else -> {}
            }
        }
    }
}

@Composable
fun HomeView(
    packageName: String, onPkgChange: (String) -> Unit,
    keywords: String, onKeywordsChange: (String) -> Unit,
    isSearching: Boolean, progress: String,
    includeSdCard: Boolean, onIncludeSdCardChange: (Boolean) -> Unit,
    searchResults: List<SearchResult>?,
    onSearch: () -> Unit,
    onOpenItem: (SearchResult) -> Unit,
    onClearSearch: () -> Unit
) {
    Column(modifier = Modifier.padding(16.dp)) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("配置:", fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = packageName, onValueChange = onPkgChange,
                    label = { Text("目标包名") }, modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = includeSdCard, onCheckedChange = onIncludeSdCardChange)
                    Text("包含 SD 卡 (/sdcard/Android/data/...)")
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                
                Text("搜索内容 (Grep -a):", fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = keywords, onValueChange = onKeywordsChange,
                    label = { Text("核心特征词 (如 akin)") }, modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        Button(onClick = onSearch, enabled = !isSearching) { Text("暴力搜") }
                    }
                )
            }
        }

        if (isSearching) {
            Spacer(modifier = Modifier.height(16.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(progress, modifier = Modifier.padding(top = 8.dp), color = Color.Red)
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (searchResults != null) {
            Row {
                Text("结果 (${searchResults.size}):", fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onClearSearch) { Text("清空") }
            }
            LazyColumn {
                if (searchResults.isEmpty()) item { Text("空空如也...", modifier = Modifier.padding(8.dp)) }
                
                items(searchResults) { item ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onOpenItem(item) },
                        elevation = CardDefaults.cardElevation(2.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(File(item.path).name, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            Text(item.path, style = MaterialTheme.typography.bodySmall, fontSize = 10.sp)
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                            Text(item.snippet, style = MaterialTheme.typography.bodySmall, color = Color.DarkGray, maxLines = 3)
                        }
                    }
                }
            }
        }
    }
}

// DataTableView & TextView 同前...
@Composable
fun DataTableView(headers: List<String>, data: List<Map<String, String>>, keywordsStr: String) {
    val scrollState = rememberScrollState()
    val keywords = keywordsStr.split(",").map { it.trim() }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.horizontalScroll(scrollState).background(Color.LightGray).padding(8.dp)) {
            headers.forEach { h -> Text(h, modifier = Modifier.width(120.dp).padding(horizontal = 4.dp), fontWeight = FontWeight.Bold, maxLines=1) }
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(data) { row ->
                val isHigh = row.values.any { v -> keywords.any { k -> v.contains(k, true) } }
                Row(modifier = Modifier.background(if (isHigh) Color.Yellow.copy(alpha=0.3f) else Color.Transparent).horizontalScroll(scrollState).padding(8.dp)) {
                    headers.forEach { h -> 
                        val v = row[h]?:""
                        val match = keywords.any { k -> v.contains(k, true) }
                        Text(v, modifier = Modifier.width(120.dp).padding(horizontal = 4.dp), fontSize=12.sp, maxLines=1, color=if(match) Color.Red else Color.Black, fontWeight=if(match) FontWeight.Bold else FontWeight.Normal)
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
fun TextView(content: String, keywordsStr: String) {
    LazyColumn(modifier = Modifier.padding(16.dp)) {
        item { Text(content) }
    }
}
