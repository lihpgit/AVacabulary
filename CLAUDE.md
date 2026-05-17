# TestApplication — 百词斩单词本（项目概述）

Android 单词学习 App，从百词斩 App 的本地数据库读取单词数据，提供单词列表和闪卡两种学习模式。

---

## 技术栈

- **语言**：Kotlin
- **UI**：Jetpack Compose（Material 3）
- **持久化**：MMKV（替代 SharedPreferences）、SQLite（只读，来自百词斩）
- **异步**：Kotlin Coroutines + StateFlow
- **包名**：`com.example.testapplication`

---

## 项目结构

```
app/src/main/
├── java/com/example/testapplication/
│   ├── App.kt                        # Application 类，初始化 MMKV
│   ├── MainActivity.kt               # 启动后直接跳转 WordListActivity 并 finish
│   └── vocab/
│       ├── Word.kt                   # 数据类
│       ├── WordBook.kt               # 词书枚举（5本）
│       ├── WordRepository.kt         # 单例，所有数据操作
│       ├── WordListActivity.kt       # 单词列表页（Compose）
│       ├── FlashCardActivity.kt      # 闪卡学习页（Compose）
│       ├── SettingsActivity.kt       # 设置页（同步/重载/刷新/使用说明）
│       ├── SyncActivity.kt           # 同步进度（导入/导出）
│       ├── ZpakParser.kt             # ZPAK0300 格式解析
│       ├── ZpkMeta.kt                # zpk 内 meta.json 解析 + 音频路径选取
│       └── WordPlaybackService.kt    # 后台播放服务（闪卡例句音频）
└── assets/baicizhan/
    ├── lookup.db                     # 单词详情（word/meanCn/sentence 等）
    ├── baicizhantopicproblem.db      # 各词书掌握状态（topic_obn < 1 = 已斩）
    ├── roadmap/                      # 词书路线图（JSON，定义 topicId 顺序）
    │   ├── road_map_409.baicizhan    # 高考
    │   ├── road_map_410.baicizhan    # 中考
    │   ├── road_map_565.baicizhan    # 六级
    │   ├── road_map_575.baicizhan    # 四级
    │   └── road_map_621.baicizhan    # 雅思
    └── zpack/                        # 每个单词的 zpk 包（含音频/图片）
        └── {bookId}/{subDir}/zp_{topicId}_{bookId}_*.zpk
```

---

## WordBook 枚举（词书定义）

```kotlin
enum class WordBook(val id: Int, val displayName: String) {
    ZHONGKAO(410, "中考"),  // 优先级最高
    GAOKAO(409, "高考"),
    CET4(575, "四级"),
    CET6(565, "六级"),
    YASI(621, "雅思");      // 优先级最低

    val roadmapFileName get() = "road_map_$id.baicizhan"
    val statusTableName  get() = "ts_learn_offline_dotopic_sync_ids_$id"
}
```

**枚举顺序即优先级**，用于跨词书去重/过滤。

---

## 核心数据模型

```kotlin
data class Word(
    val topicId: Int,
    val word: String,
    val accent: String,       // 音标
    val meanCn: String,       // 中文释义
    val sentence: String,     // 例句
    val sentenceTrans: String,
    val masteredInDb: Boolean // true = 已斩（topic_obn < 1.0）
)
```

---

## WordRepository（单例）

**关键 StateFlow：**

| Flow | 说明 |
|------|------|
| `loadState` | `Idle / Loading / Success(words) / Error` |
| `syncState` | `Idle / Syncing / Success / Error` |
| `overrides: Map<Int, Boolean>` | 用户手动切换的已斩状态，覆盖 DB 值，MMKV 持久化 |
| `readTopicIdsUnmastered/Mastered` | 当前轮次已浏览的 topicId，按未斩/已斩分桶 |
| `crossBookCounts: Map<Int, Int>` | 每个 topicId 出现在几本词书中（1–5） |
| `bookTopicIds: Map<WordBook, Set<Int>>` | 各词书的 topicId 集合，用于跨词书过滤 |
| `globalFilteredCounts: Pair<Int,Int>?` | 全词书去重后的（未斩数, 已斩数） |

**关键方法：**
- `loadWords(book, forceReload)` — 加载词书（有缓存）
- `effectiveMastered(topicId, dbState, overrides)` — 取实际掌握状态
- `toggleMastered(topicId, currentEffective)` — 切换已斩/未斩并持久化
- `markRead(topicId, filterType)` / `clearReadMarks(filterType)` — 轮次已读标记
- `syncFromAssets()` — 强制从 assets 覆盖 DB 文件（重载词库）
- `readZpk(topicId)` — 返回 zpk 包的 `Map<String, ByteArray>`
- `exportProgress()` / `importProgress(json)` — 进度导入导出

**MMKV 命名空间：**
- `word_overrides` — 已斩 override（JSON）
- `read_marks` — 已读标记，key = `read_topic_ids_{bookId}_{filterType}`
- `word_list_prefs` — 列表页偏好（选中词书 ID、过滤开关）
- `flashcard_prefs` — 闪卡偏好（round 计数，key = `round_{bookId}_{filterType}`）

---

## 业务逻辑要点

### 跨词书过滤（去重）
```kotlin
// 排除所有优先级更高的词书的 topicId
val excludedTopicIds = WordBook.entries
    .takeWhile { it != selectedBook }
    .flatMap { bookTopicIds[it] ?: emptySet() }
    .toSet()
```
WordListActivity 和 FlashCardActivity 都用这同一个模式。

### 已斩状态优先级
`overrides` 覆盖 DB 值：`effectiveMastered = overrides[id] ?: masteredInDb`

### 已读分桶
未斩列表和已斩列表的已读标记独立存储，切换 tab 不会互相污染。

### 重载词库后自动刷新
- `SettingsActivity` 的 `SyncState.Success` 只弹 snackbar，不调 `resetSyncState()`
- `WordListActivity` 在 `LaunchedEffect(syncState)` 中监听到 Success 后，先 `loadWords(forceReload=true)` 再 `resetSyncState()`

---

## WordListActivity 功能

- **默认词书**：中考（ZHONGKAO）
- **Tab**：未斩 / 已斩，数量动态显示
- **编辑模式**：点"编辑"按钮，每条目右侧出现"斩"按钮（未斩=红色 / 已斩=灰色中划线），点击切换掌握状态并朗读单词，切换 tab/词书自动退出编辑模式
- **单词区（左 1/5）**：点击进入闪卡，从对应索引位置开始
- **释义区（右 4/5）**：点击显示翻译并朗读（zpk 音频优先，TTS 兜底）
- **已读标记**：绿色小方块，闪卡浏览后自动标记，轮次结束时清空
- **跨词书颜色**（出现在 N 本词书中）：
  - 5本 → `0xFFFF5252`（亮红）
  - 4本 → `0xFFE040FB`（亮紫）
  - 3本 → `0xFFFFD740`（琥珀黄）
  - 2本 → `0xFF69F0AE`（薄荷绿）
  - 1本 → 默认色
- **右侧悬浮字母索引**（A–Z + #）：拖拽快速跳转

---

## FlashCardActivity 功能

- 接收 `EXTRA_FILTER`（unmastered/mastered）、`EXTRA_START_INDEX`、`EXTRA_BOOK_ID`、`EXTRA_CROSS_BOOK_FILTER`
- 手势：左右滑动翻页，点击切换正/背面
- 正面：单词 + 音标；背面：中文释义 + 例句 + 例句翻译
- 长按例句 → 覆盖层大字显示，点击播放例句音频
- 平板模式（`maxWidth >= 600.dp`）：字号整体增大（译文 +15sp，覆盖层例句 95sp）
- 轮次计数持久化：key = `round_{bookId}_{filterType}`（MMKV `flashcard_prefs`）

---

## SettingsActivity 功能

三个操作条目 + 使用说明：
1. **同步学习进度** → 启动 SyncActivity（导入/导出）
2. **重载词库** → 确认对话框 → `syncFromAssets()`，覆盖 assets 内容到 filesDir
3. **刷新当前词书** → `loadWords(forceReload=true)`
4. **使用说明** → 对话框，覆盖所有功能说明（颜色含义、操作方式等）

---

## zpk 资产说明

- **格式**：ZPAK0300，无加密无压缩（见 `ZPAK_FORMAT_SPEC.md`）
- **文件名**：`zp_{topicId}_{bookId}_{index}_{timestamp}.zpk`
- **内容**：`meta.json`（单词信息）+ 音频 MP3（`meta.word_audio` 或 `uk_*.mp3`）
- **图片已剥离**：运行过 `assets/baicizhan/zpk_strip_images.py`，IMAGE_EXTS = `{.jpg, .jpeg, .png, .gif, .webp, .bmp}`
- **APK 压缩**：`noCompress += "zpk"` 已移除，APK 工具正常压缩 zpk（~3.6GB），`assets.open()` 透明解压

---

## 注意事项

- **部分代码由 Cursor AI 生成**（已读分桶、WordCellScaledText、FloatingAlphabetSidebar 等），修改时不要覆盖
- `toggleMastered` 是普通函数（非 suspend），直接调用即可
- `effectiveMastered` 需要传入当前 `overrides` StateFlow 值（不能直接访问 repository 内部）
- 词书缓存：`wordCache: Map<WordBook, List<Word>>`，切换词书时命中缓存无需重读 DB
- 设备上的 DB 路径：`/data/data/com.example.testapplication/files/baicizhan/`（`filesDir` 下）
- 百词斩原始 DB 路径：`/sdcard/Android/data/com.jiongji.andriod.card/files/baicizhan/`
