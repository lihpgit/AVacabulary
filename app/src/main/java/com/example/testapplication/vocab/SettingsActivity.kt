package com.example.testapplication.vocab

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.launch

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            com.example.testapplication.ui.theme.TestApplicationTheme {
                SettingsScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { WordRepository.getInstance(context) }
    val syncState by repository.syncState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    var showSyncDialog by remember { mutableStateOf(false) }
    var showGuide by remember { mutableStateOf(false) }
    var exportingLists by remember { mutableStateOf(false) }

    // 重载词库确认对话框
    if (showSyncDialog) {
        AlertDialog(
            onDismissRequest = { showSyncDialog = false },
            title = { Text("重新加载内置词库") },
            text = { Text("将把 assets 目录内的数据库文件重新覆盖到本地存储，适用于你更新了 assets 内的 DB 文件并重新安装 App 后。\n\n你手动调整过的已斩/未斩状态不受影响。确认继续？") },
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
    // 注意：不在此处调用 resetSyncState()，由 WordListActivity 统一处理
    // 以确保 WordListActivity 恢复时能捕获 Success 状态并刷新词表
    LaunchedEffect(syncState) {
        when (val s = syncState) {
            is SyncState.Success -> snackbarHostState.showSnackbar("重载成功，返回后自动刷新")
            is SyncState.Error   -> {
                snackbarHostState.showSnackbar("重载失败：${s.message}")
                repository.resetSyncState() // Error 只需清状态，不涉及刷新
            }
            else -> {}
        }
    }

    // 使用说明弹窗
    if (showGuide) {
        AlertDialog(
            onDismissRequest = { showGuide = false },
            title = { Text("使用说明") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    GuideSection("词书切换",
                        "主界面左上角的词书按钮可切换词书（中考、高考、四级、六级、雅思）。点击后弹出选择框，选择后自动加载对应词书。")
                    GuideSection("未斩 / 已斩",
                        "单词分为「未斩」和「已斩」两个标签页。未斩 = 还需要学习，已斩 = 已经掌握。在闪卡页面点击「斩」按钮可切换单词状态。")
                    GuideSection("闪卡学习",
                        "· 单词列表：左侧英文单词点击后进闪卡；右侧释义区默认隐藏，点击后会显示中文释义（最多两行）并播报单词读音（与闪卡相同优先使用词典音频）。\n" +
                        "· 闪卡内：闪卡会自动朗读单词并显示释义、音标、英文释义和例句。\n\n" +
                        "· 点击卡片可暂停/继续自动播放\n" +
                        "· 暂停时会显示中文释义和例句翻译\n" +
                        "· 左右滑动或点击底部按钮切换上/下一个单词\n" +
                        "· 右下角悬浮按钮控制例句朗读")
                    GuideSection("语境猜词",
                        "主界面右上角的「猜词」按钮进入，用例句语境推断单词意思，比直接背释义记得更牢。\n\n" +
                        "· 卡片显示单词、音标和例句（例句中的目标单词会加粗高亮）\n" +
                        "· 进入每个单词时自动朗读（朗读方式可设置，见下）\n" +
                        "· 点击卡片：显示翻译（中文释义、英文释义、例句翻译），再点一下隐藏，可来回切换\n" +
                        "· 底部「上一个 / 下一个」翻页；左右滑动也可翻页（右滑=上一个，左滑=下一个）\n" +
                        "· 切换单词时翻译自动隐藏，方便自测\n" +
                        "· 顶部「朗读·X」可切换朗读方式：只读单词 / 只读例句 / 都读（先单词或先例句）\n" +
                        "· 浏览进度按「词书 + 未斩/已斩」自动保存，下次进入从上次的位置继续\n" +
                        "· 顶部显示进度和用时\n\n" +
                        "左下角悬浮「斩」按钮：点击切换已斩/未斩（撤回斩），与闪卡一致，可拖动改变位置。已斩时卡片右上角显示淡色「斩」印章。")
                    GuideSection("平板猜词（横屏 + 鼠标）",
                        "平板（最小宽度 ≥ 600dp）上「猜词」页跟随系统自动旋转，手机仍锁竖屏。\n\n" +
                        "· 未显示翻译时：单词 / 音标 / 例句从上到下大字显示，方便看例句猜词\n" +
                        "· 点卡片显示翻译后：单词变成居中的半透明大字水印，例句、释义、翻译清晰叠在上层，充分利用横屏空间\n\n" +
                        "连接鼠标时可用滚轮翻页：\n" +
                        "· 向上滚 → 上一个\n" +
                        "· 向下滚 → 下一个\n" +
                        "· 左键点击卡片 → 切换翻译显隐\n" +
                        "· 内容较长时，滚轮先用来浏览内容，滚到边界再多滚一下才翻页")
                    GuideSection("跨词书过滤",
                        "主界面右侧的「过滤」按钮开启后，会隐藏在更高优先级词书中已出现的单词。\n\n" +
                        "优先级：中考 > 高考 > 四级 > 六级 > 雅思\n\n" +
                        "例如：开启过滤后，四级词书会隐藏掉中考和高考中已包含的单词，避免重复学习。\n\n" +
                        "过滤按钮上会显示「已过滤 X/Y」，X 为去重后的总未斩数，Y 为总已斩数。")
                    GuideSection("单词颜色",
                        "未斩列表中的单词会根据跨词书出现次数用不同颜色标记：\n\n" +
                        "· 白色：仅在当前词书出现\n" +
                        "· 薄荷绿：在 2 本词书中出现\n" +
                        "· 琥珀黄：在 3 本词书中出现\n" +
                        "· 亮紫：在 4 本词书中出现\n" +
                        "· 亮红：在 5 本词书中都出现")
                    GuideSection("搜索",
                        "主界面的搜索框支持按单词或中文释义搜索，输入关键词即可实时过滤。\n\n" +
                        "右侧半透明悬浮字母条（A–Z + #）与当前列表联动：列表滚动时高亮首可见词对应字母；点击或上下拖拽可跳转到该字母（或 #）下首个单词；当前列表里没有该首字母单词时对应字母显示为灰色。")
                    GuideSection("已读标记",
                        "在闪卡中浏览过的单词会在列表中显示绿色小方块标记。打开列表时会自动滚动到第一个未读单词。点击闪卡页面的\"清除已读\"按钮可重置标记，方便新一轮复习。")
                    GuideSection("同步进度",
                        "设置中的\"同步学习进度\"可在两部手机间同步已斩/未斩状态和已读标记，两种方式：\n\n" +
                        "【方式一 · 文件分享（推荐，最稳）】\n" +
                        "· 发送方点\"分享进度文件\"，在系统分享面板选 蓝牙 / 微信 / QQ / 快传 等任意方式发给对方\n" +
                        "· 接收方收到文件后，在本页点\"从文件导入进度\"，选中该文件即可覆盖本地进度\n" +
                        "· 无需同一网络，跨机型最稳\n\n" +
                        "【方式二 · 扫码直传】\n" +
                        "· 两机连同一 WiFi，或一方开热点、另一方连接该热点\n" +
                        "· 发送方出二维码，接收方扫码即时传输\n" +
                        "· 部分机型在\"流量+热点\"下可能受限，建议改用方式一")
                    GuideSection("重载词库",
                        "当你更新了内置数据库文件（如新增词书、修正数据）并重新安装 App 后，点击\"重载词库\"会用最新的 assets 数据覆盖本地旧数据。你手动调整过的已斩/未斩状态不受影响。")
                    GuideSection("导出未斩/已斩列表（详细教程）",
                        "【做什么】\n" +
                        "在「设置 → 数据管理」中点击「导出未斩/已斩列表（Kotlin注释）」，会按与本 App 相同的规则导出「中考、高考、四级、六级」四本词书（雅思不包含）；掌握判定 = 数据库 + 你在闪卡里改的斩/未斩。\n\n" +
                        "【词表是否与列表一致】\n" +
                        "与单词列表页的「过滤」开关完全一致（同名偏好 cross_book_filter）：\n" +
                        "· 过滤「开」：导出的是列表里看到的那份词——即已从更高优先级词书（中考>高考>四级>六级>雅思）中剔除重复 topic 后的结果。\n" +
                        "· 过滤「关」：导出该词书全部词。\n" +
                        "导出正文第一行注释会标明本次是「过滤开」还是「过滤关」。列表顶部搜索框仅用于界面筛选，不参与导出。\n\n" +
                        "【生成什么文件】\n" +
                        "在应用私有「文档」目录下创建文件夹 word_export_kotlin，共 8 个纯文本文件，文件名里的数字为词书 ID：\n" +
                        "· UnmasteredWords_410.kt / MasteredWords_410.kt（中考）\n" +
                        "· UnmasteredWords_409.kt / MasteredWords_409.kt（高考）\n" +
                        "· UnmasteredWords_575.kt / MasteredWords_575.kt（四级）\n" +
                        "· UnmasteredWords_565.kt / MasteredWords_565.kt（六级）\n\n" +
                        "每个文件内容为 Kotlin 风格的注释行，格式类似：// 单词 + 对齐空格 + 中文释义。\n\n" +
                        "【操作步骤】\n" +
                        "1）在单词列表页设好「过滤」开关（再导出才会与当前列表范围一致）。\n" +
                        "2）确保已能正常打开单词列表。\n" +
                        "3）进入设置点击导出，等待结束；底部提示路径或失败原因。\n" +
                        "4）导出完成后会自动恢复你在列表中选中的词书。\n\n" +
                        "【如何把文件拷到电脑】\n" +
                        "路径一般为：Android/data/你的包名/files/Documents/word_export_kotlin/\n" +
                        "可用 USB、Android Studio Device File Explorer 或 adb pull。\n\n" +
                        "【注意】\n" +
                        "· 文件在应用私有目录，卸载 App 可能丢失，请及时备份。\n" +
                        "· 词量多时需要一点时间，请勿重复狂点导出。")
                    GuideSection("刷新",
                        "点击\"刷新\"会重新从本地数据库读取当前词书，适用于你在其他地方修改了数据后需要更新列表的场景。")
                }
            },
            confirmButton = {
                TextButton(onClick = { showGuide = false }) { Text("知道了") }
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = { (context as? SettingsActivity)?.finish() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            // ── 数据管理 ──
            SectionHeader("数据管理")

            SettingsItem(
                title = "同步学习进度",
                subtitle = "在两部手机间同步已斩/未斩状态",
                onClick = {
                    context.startActivity(Intent(context, SyncActivity::class.java))
                }
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            SettingsItem(
                title = "导出未斩/已斩列表（Kotlin注释）",
                subtitle = if (exportingLists) "正在导出…"
                           else "中考～六级共 8 个文件；词表范围与列表页「过滤」开关一致",
                onClick = {
                    if (exportingLists) return@SettingsItem
                    scope.launch {
                        exportingLists = true
                        val result = WordListKtExporter.exportToDocuments(context, repository)
                        exportingLists = false
                        result.fold(
                            onSuccess = { path ->
                                val prefs = MMKV.mmkvWithID("word_list_prefs")
                                val filtered = prefs.decodeBool("cross_book_filter", false)
                                val hint = if (filtered) "（列表「过滤」已开启）" else "（列表「过滤」关闭）"
                                snackbarHostState.showSnackbar(
                                    "已导出 $hint\n$path",
                                    withDismissAction = true
                                )
                            },
                            onFailure = { e ->
                                snackbarHostState.showSnackbar(
                                    "导出失败:\n${e.message ?: "未知错误"}",
                                    withDismissAction = true
                                )
                            }
                        )
                    }
                },
                trailing = if (exportingLists) {
                    {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                } else null
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            SettingsItem(
                title = "重载词库",
                subtitle = if (syncState is SyncState.Syncing) "正在重载…"
                           else "用 assets 内的最新数据库覆盖本地数据",
                onClick = { if (syncState !is SyncState.Syncing) showSyncDialog = true },
                trailing = {
                    if (syncState is SyncState.Syncing) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                }
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            SettingsItem(
                title = "刷新当前词书",
                subtitle = "重新从本地数据库加载单词列表",
                onClick = {
                    scope.launch {
                        val book = repository.currentBook
                        if (book != null) {
                            repository.loadWords(book, forceReload = true)
                            snackbarHostState.showSnackbar("已刷新")
                        }
                    }
                }
            )

            Spacer(Modifier.height(16.dp))

            // ── 帮助 ──
            SectionHeader("帮助")

            SettingsItem(
                title = "使用说明",
                subtitle = "了解背单词功能的详细用法",
                onClick = { showGuide = true }
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
    )
}

@Composable
private fun SettingsItem(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(
                subtitle,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        } else {
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun GuideSection(title: String, content: String) {
    Text(
        text = title,
        fontWeight = FontWeight.Bold,
        fontSize = 15.sp,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
    )
    Text(
        text = content,
        fontSize = 14.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        lineHeight = 20.sp
    )
}
