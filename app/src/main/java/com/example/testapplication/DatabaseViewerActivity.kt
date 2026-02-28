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
    HOME, TABLE_LIST, DATA_TABLE, TEXT_VIEW, SQL_CONSOLE
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
    
    var packageName by remember { mutableStateOf("com.jiongji.andriod.card") }
    
    var currentState by remember { mutableStateOf(ViewerState.HOME) }
    
    var fileList by remember { mutableStateOf<List<String>>(emptyList()) }
    var globalSearchResults by remember { mutableStateOf<List<SearchResult>?>(null) }
    var searchKeywords by remember { mutableStateOf("akin") }
    var isSearching by remember { mutableStateOf(false) }
    var searchProgress by remember { mutableStateOf("") }
    var includeSdCard by remember { mutableStateOf(true) }

    // 详情页数据
    var currentDbName by remember { mutableStateOf("") }
    var currentTableName by remember { mutableStateOf("") }
    var currentTableData by remember { mutableStateOf<List<Map<String, String>>>(emptyList()) }
    var currentTableHeaders by remember { mutableStateOf<List<String>>(emptyList()) }
    var currentTableList by remember { mutableStateOf<List<String>>(emptyList()) }
    
    var currentTextTitle by remember { mutableStateOf("") }
    var currentTextContent by remember { mutableStateOf("") }

    // SQL 控制台数据
    var currentSql by remember { mutableStateOf("") }
    var sqlResultHeaders by remember { mutableStateOf<List<String>>(emptyList()) }
    var sqlResultData by remember { mutableStateOf<List<Map<String, String>>>(emptyList()) }
    var sqlError by remember { mutableStateOf("") }

    // 扫描当前目录文件
    fun scanFiles() {
        scope.launch(Dispatchers.IO) {
            val cmd = "ls -F /data/data/$packageName/databases" // 默认切回 databases 方便找库
            val result = RootUtils.execRootCmd(cmd)
            val files = result.split("\n")
                .map { it.trim() }
                .filter { it.endsWith(".db") } // 只列 DB
            
            withContext(Dispatchers.Main) {
                fileList = files
            }
        }
    }

    // 全局暴力搜索
    fun performGlobalSearch() {
        val keyword = searchKeywords.trim()
        if (keyword.isEmpty()) return

        scope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                isSearching = true
                searchProgress = "正在进行全盘 grep 暴力搜索..."
                globalSearchResults = emptyList()
            }

            val paths = mutableListOf<String>()
            paths.add("/data/data/$packageName")
            if (includeSdCard) {
                paths.add("/sdcard/Android/data/$packageName")
            }

            val pathArgs = paths.joinToString(" ")
            val cmd = "grep -rnia \"$keyword\" $pathArgs"

            try {
                val rawOutput = RootUtils.execRootCmd(cmd)
                val results = mutableListOf<SearchResult>()
                val lines = rawOutput.split("\n")
                
                for (line in lines) {
                    if (line.isBlank()) continue
                    val parts = line.split(":", limit = 3)
                    if (parts.size >= 3) {
                        val path = parts[0].trim()
                        val lineNum = parts[1]
                        val content = parts[2].trim()
                        
                        if (path.contains("/lib/") || path.contains("/cache/")) continue
                        
                        val type = if (path.endsWith(".db")) "DB" else "FILE"
                        val snippetDisplay = if (content.length > 100) content.take(100) + "..." else content
                        
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
                        Toast.makeText(context, "全盘搜索未找到", Toast.LENGTH_LONG).show()
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

    // 执行自定义 SQL
    fun executeSql(sql: String) {
        if (sql.isBlank()) return
        scope.launch(Dispatchers.IO) {
            // 使用 temp_view.db (假设之前已经 openDatabase 复制过了)
            // 如果还没复制，这里可能会报错，所以逻辑上得先选库
            val localFile = File(context.cacheDir, "temp_view.db")
            if (!localFile.exists()) return@launch

            try {
                val db = SQLiteDatabase.openDatabase(localFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
                val cursor = db.rawQuery(sql, null)
                val headers = cursor.columnNames.toList()
                val data = mutableListOf<Map<String, String>>()
                
                while (cursor.moveToNext()) {
                    val row = mutableMapOf<String, String>()
                    for (col in headers) {
                        val idx = cursor.getColumnIndex(col)
                        row[col] = cursor.getString(idx) ?: "NULL"
                    }
                    data.add(row)
                }
                cursor.close()
                db.close()

                withContext(Dispatchers.Main) {
                    sqlResultHeaders = headers
                    sqlResultData = data
                    sqlError = ""
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    sqlResultHeaders = emptyList()
                    sqlResultData = emptyList()
                    sqlError = "执行错误: ${e.message}"
                }
            }
        }
    }

    // 打开文本文件逻辑
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

    // 打开文件/库
    fun openItem(path: String, type: String = "DB") {
        if (type == "DB") {
            scope.launch(Dispatchers.IO) {
                val localFile = File(context.cacheDir, "temp_view.db")
                // 如果是绝对路径直接用，如果是相对路径加前缀 (这里简化处理，假设 fileList 传进来的是文件名，搜索传的是绝对)
                val fullPath = if (path.startsWith("/")) path else "/data/data/$packageName/databases/$path"
                
                RootUtils.execRootCmd("cp \"$fullPath\" ${localFile.absolutePath}")
                RootUtils.execRootCmd("chmod 666 ${localFile.absolutePath}")
                
                try {
                    val db = SQLiteDatabase.openDatabase(localFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
                    val cursor = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null)
                    val tables = mutableListOf<String>()
                    while (cursor.moveToNext()) tables.add(cursor.getString(0))
                    cursor.close()
                    db.close()
                    
                    withContext(Dispatchers.Main) {
                        currentDbName = File(fullPath).name
                        currentTableList = tables
                        currentState = ViewerState.TABLE_LIST
                        // 重置 SQL 状态
                        currentSql = "SELECT count(*) FROM ${tables.firstOrNull() ?: "table"}"
                        sqlResultData = emptyList()
                        sqlError = ""
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { Toast.makeText(context, "打开DB失败", Toast.LENGTH_SHORT).show() }
                }
            }
        }
    }

    // 打开表 (查看前 50 条)
    fun openTable(tableName: String) {
        scope.launch(Dispatchers.IO) {
            val localFile = File(context.cacheDir, "temp_view.db")
            val db = SQLiteDatabase.openDatabase(localFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            val c = db.rawQuery("SELECT * FROM $tableName LIMIT 50", null)
            val headers = c.columnNames.toList()
            val data = mutableListOf<Map<String, String>>()
            while (c.moveToNext()) {
                val row = mutableMapOf<String, String>()
                for (h in headers) row[h] = c.getString(c.getColumnIndex(h))?:"NULL"
                data.add(row)
            }
            c.close()
            db.close()
            withContext(Dispatchers.Main) {
                currentTableName = tableName
                currentTableHeaders = headers
                currentTableData = data
                currentState = ViewerState.DATA_TABLE
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        text = when(currentState) {
                            ViewerState.HOME -> "Root文件/DB取证"
                            ViewerState.TABLE_LIST -> "$currentDbName (表列表)"
                            ViewerState.DATA_TABLE -> currentTableName
                            ViewerState.TEXT_VIEW -> currentTextTitle
                            ViewerState.SQL_CONSOLE -> "SQL控制台: $currentDbName"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontSize = 16.sp
                    ) 
                },
                navigationIcon = {
                    if (currentState != ViewerState.HOME) {
                        IconButton(onClick = { 
                            if (currentState == ViewerState.SQL_CONSOLE || currentState == ViewerState.DATA_TABLE) 
                                currentState = ViewerState.TABLE_LIST 
                            else currentState = ViewerState.HOME 
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (currentState) {
                ViewerState.HOME -> HomeView(
                    packageName = packageName, onPkgChange = { packageName = it },
                    keywords = searchKeywords, onKeywordsChange = { searchKeywords = it },
                    isSearching = isSearching, progress = searchProgress,
                    includeSdCard = includeSdCard, onIncludeSdCardChange = { includeSdCard = it },
                    searchResults = globalSearchResults,
                    fileList = fileList,
                    onScan = ::scanFiles,
                    onSearch = ::performGlobalSearch,
                    onOpenPath = { path -> openItem(path, if(path.endsWith(".db")) "DB" else "FILE") },
                    onOpenFileName = { name -> openItem(name, "DB") },
                    onClearSearch = { globalSearchResults = null }
                )
                ViewerState.TABLE_LIST -> TableListView(
                    tables = currentTableList, 
                    onOpenTable = ::openTable,
                    onOpenSql = { currentState = ViewerState.SQL_CONSOLE }
                )
                ViewerState.DATA_TABLE -> DataTableView(currentTableHeaders, currentTableData, "")
                ViewerState.TEXT_VIEW -> TextView(currentTextContent, "")
                ViewerState.SQL_CONSOLE -> SqlConsoleView(
                    sql = currentSql,
                    onSqlChange = { currentSql = it },
                    onExecute = ::executeSql,
                    headers = sqlResultHeaders,
                    data = sqlResultData,
                    error = sqlError
                )
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
    fileList: List<String>,
    onScan: () -> Unit, onSearch: () -> Unit,
    onOpenPath: (String) -> Unit,
    onOpenFileName: (String) -> Unit,
    onClearSearch: () -> Unit
) {
    Column(modifier = Modifier.padding(16.dp)) {
        // 配置区
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(modifier = Modifier.padding(16.dp)) {
                OutlinedTextField(value = packageName, onValueChange = onPkgChange, label = { Text("目标包名") }, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = includeSdCard, onCheckedChange = onIncludeSdCardChange)
                    Text("包含 SD 卡")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onScan, modifier = Modifier.weight(1f)) { Text("列出 Databases") }
                    Button(onClick = onSearch, enabled = !isSearching, modifier = Modifier.weight(1f)) { Text("暴力搜关键词") }
                }
                OutlinedTextField(value = keywords, onValueChange = onKeywordsChange, label = { Text("关键词") }, modifier = Modifier.fillMaxWidth())
            }
        }

        if (isSearching) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top=8.dp))
            Text(progress, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn {
            if (searchResults != null) {
                item { 
                    Row {
                        Text("搜索结果:", fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.weight(1f))
                        TextButton(onClick = onClearSearch) { Text("清除") }
                    }
                }
                if (searchResults.isEmpty()) item { Text("无匹配结果") }
                items(searchResults) { item ->
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical=4.dp).clickable { onOpenPath(item.path) }) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(File(item.path).name, fontWeight = FontWeight.Bold)
                            Text(item.path, style = MaterialTheme.typography.bodySmall)
                            Text(item.snippet, color = Color.Gray, maxLines=2)
                        }
                    }
                }
            } else {
                item { Text("数据库列表:", fontWeight = FontWeight.Bold) }
                items(fileList) { file ->
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical=4.dp).clickable { onOpenFileName(file) }) {
                        Text(file, modifier = Modifier.padding(16.dp), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun TableListView(tables: List<String>, onOpenTable: (String) -> Unit, onOpenSql: () -> Unit) {
    Column(modifier = Modifier.padding(16.dp)) {
        Button(onClick = onOpenSql, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)) {
            Text("打开 SQL 控制台 (执行 COUNT/聚合)")
        }
        Spacer(modifier = Modifier.height(8.dp))
        LazyColumn {
            items(tables) { table ->
                Card(modifier = Modifier.fillMaxWidth().padding(vertical=4.dp).clickable { onOpenTable(table) }) {
                    Text(table, modifier = Modifier.padding(16.dp))
                }
            }
        }
    }
}

@Composable
fun SqlConsoleView(
    sql: String, onSqlChange: (String) -> Unit, onExecute: (String) -> Unit,
    headers: List<String>, data: List<Map<String, String>>, error: String
) {
    Column(modifier = Modifier.padding(16.dp)) {
        OutlinedTextField(
            value = sql, onValueChange = onSqlChange,
            label = { Text("SQL 语句") },
            modifier = Modifier.fillMaxWidth().height(120.dp),
            placeholder = { Text("SELECT count(*) FROM table_name WHERE status=1") }
        )
        Button(onClick = { onExecute(sql) }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Text("执行查询")
        }
        
        if (error.isNotEmpty()) {
            Text(error, color = Color.Red)
        }
        
        HorizontalDivider()
        
        // 结果展示
        if (data.isNotEmpty()) {
            Text("查询结果: ${data.size} 行", fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical=4.dp))
            DataTableView(headers, data, "")
        } else {
            Text("无结果", modifier = Modifier.padding(top=8.dp))
        }
    }
}

@Composable
fun DataTableView(headers: List<String>, data: List<Map<String, String>>, keywordsStr: String) {
    val scrollState = rememberScrollState()
    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.horizontalScroll(scrollState).background(Color.LightGray).padding(8.dp)) {
            headers.forEach { h -> Text(h, modifier = Modifier.width(120.dp).padding(horizontal = 4.dp), fontWeight = FontWeight.Bold, maxLines=1) }
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(data) { row ->
                Row(modifier = Modifier.horizontalScroll(scrollState).padding(8.dp)) {
                    headers.forEach { h -> 
                        Text(row[h]?:"", modifier = Modifier.width(120.dp).padding(horizontal = 4.dp), fontSize=12.sp, maxLines=1)
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
fun TextView(content: String, keywordsStr: String) {
    LazyColumn(modifier = Modifier.padding(16.dp)) { item { Text(content) } }
}
