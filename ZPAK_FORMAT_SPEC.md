# 百词斩 ZPAK0300 文件格式规范

## 概述

ZPAK 是百词斩 App 的单词资源打包格式。每个 zpk 文件对应一个单词，内部包含 JSON 元数据、MP3 音频、图片等资源。**无加密、无压缩**，文件数据原样存储。

文件命名规则：`zp_{topicId}_409_0_{timestamp}.zpk`，其中 `topicId` 是单词 ID。

---

## 文件结构总览

```
┌──────────────────────────────────┐ offset 0x00
│         Fixed Header (128B)      │
├──────────────────────────────────┤ offset 0x80
│         File Data Area           │
│   (各文件内容依次紧密排列)         │
│   - meta.json                    │
│   - resource.json                │
│   - wiki.json                    │
│   - xxx.jpg                      │
│   - xxx.mp3 (例句朗读)           │
│   - us_xxx.mp3 (美式发音)        │
│   - xxx.png                      │
│   - uk_xxx.mp3 (英式发音)        │
├──────────────────────────────────┤ offset = header.indexOffset
│     Index Table (48B × N)        │
├──────────────────────────────────┤ offset = header.nameOffset
│     Filename Table (\n 分隔)      │
└──────────────────────────────────┘
```

---

## 1. Fixed Header (128 字节)

所有整数均为 **小端序 (Little-Endian)**。

| 偏移 | 大小 | 类型 | 说明 |
|------|------|------|------|
| 0x00 | 8B | char[8] | Magic: `ZPAK0300` (ASCII) |
| 0x08 | 4B | uint32 | 数据区起始偏移 (固定 128 = 0x80) |
| 0x0C | 4B | uint32 | 文件条目数量 N |
| 0x10 | 8B | uint64 | indexOffset - 索引表起始偏移 |
| 0x18 | 8B | uint64 | nameOffset - 文件名表起始偏移 |
| 0x20 | 4B | uint32 | 文件名表总大小(字节) |
| 0x24-0x7F | - | - | 其他字段 / 保留 (解析时可忽略) |

---

## 2. Index Table (每条 48 字节)

位于 `indexOffset` 处，共 N 条，每条 48 字节：

| 条目内偏移 | 大小 | 类型 | 说明 |
|-----------|------|------|------|
| 0 | 8B | uint64 | 文件数据在 zpk 中的绝对偏移 |
| 8 | 8B | - | hash/校验 (可忽略) |
| 16 | 4B | uint32 | 文件原始大小 (字节) |
| 20 | 4B | uint32 | 存储大小 (= 原始大小, 无压缩) |
| 24-47 | - | - | 其他字段 (可忽略) |

---

## 3. Filename Table

位于 `nameOffset` 处，是一个 UTF-8 字符串块，各文件名以 `\n` (0x0A) 分隔，末尾以 `\0` (0x00) 终止。

文件名顺序与 Index Table 条目一一对应。

典型内容：
```
meta.json
resource.json
wiki.json
fbd3d74be33a645b04e9b2136aeaf151_118640.jpg
bhl26pvfp2nz27kb9da48wwg2kk7pyzj.mp3
us_ache_20230921145250741.mp3
d_24_45_0_3_20150808223103.png
uk_ache_20230921145252308.mp3
```

---

## 4. 内部文件说明

每个 zpk 一般包含 8 个文件：

### 4.1 meta.json - 单词基础信息
```json
{
  "word": "ache",
  "accent": "/eɪk/",
  "mean_cn": "n.疼痛；  v.觉得疼痛",
  "mean_en": "(often in compounds) a continuous feeling of pain...",
  "sentence": "Mark had an ache in his back.",
  "sentence_trans": "马克背部疼痛。",
  "sentence_phrase": "had an ache in",
  "sentence_audio": "bhl26pvfp2nz27kb9da48wwg2kk7pyzj.mp3",
  "word_audio": "us_ache_20230921145250741_ddfb07235228c2ef560b.mp3",
  "image_file": "fbd3d74be33a645b04e9b2136aeaf151_118640.jpg",
  "deformation_img": "d_24_45_0_3_20150808223103.png",
  "topic_id": 45,
  "word_level_id": 409,
  "word_etyma": "",
  "tag_id": 0,
  "cloze_data": {
    "syllable": "ache",
    "cloze": "[a]che",
    "options": ["e|i|o|u"],
    "tips": [["ear[a]che", "[a]chenium"]]
  }
}
```

### 4.2 resource.json - 完整学习资源
```json
{
  "word": {
    "topicId": 45,
    "word": "ache",
    "accentUs": "/eɪk/",
    "accentUk": "/eɪk/",
    "audioUs": "us_ache_xxx.mp3",
    "audioUk": "uk_ache_xxx.mp3"
  },
  "mnemonic": {
    "type": 3,
    "content": "啊（a），扯（che）着"疼"（ache）。"
  },
  "cnMean": [
    { "mId": 106, "meanType": "n.", "mean": "疼痛" },
    { "mId": 107, "meanType": "v.", "mean": "觉得疼痛" }
  ],
  "enMean": [
    { "mId": 113789, "meanType": "n.", "mean": "a continuous feeling of pain..." }
  ],
  "sentences": [
    {
      "sId": 731029,
      "sentenceEn": "Mark had an ache in his back.",
      "translate": "马克背部疼痛。",
      "audio": "bhl26pvfp2nz27kb9da48wwg2kk7pyzj.mp3",
      "phrase": "had an ache in"
    }
  ],
  "phrases": [
    { "phrase": "stomach ache", "mean": "胃疼" }
  ],
  "variant": [
    { "type": "现在分词", "variant": "aching" },
    { "type": "过去式", "variant": "ached" }
  ],
  "synonyms": [
    { "word": "pain" }, { "word": "hurt" }
  ],
  "similars": [
    { "word": "cache", "meanType": "n.", "mean": "贮藏物" }
  ]
}
```

### 4.3 音频文件
- `us_xxx.mp3` — 美式发音 (~16KB)
- `uk_xxx.mp3` — 英式发音 (~16KB)  
- 随机哈希名.mp3 — 例句朗读 (~90KB)
- 均为标准 MP3 格式 (ID3v2 或裸 MPEG frame)

### 4.4 图片文件 (本项目可忽略)
- `.jpg` — 例句配图
- `.png` — 变形助记图

---

## 5. 解析伪代码 (Android/Kotlin)

```kotlin
fun parseZpk(file: File): Map<String, ByteArray> {
    val data = file.readBytes()
    val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
    
    // 1. 读 header
    val magic = String(data, 0, 8)          // "ZPAK0300"
    val numEntries = buf.getInt(0x0C)
    val indexOffset = buf.getLong(0x10)
    val nameOffset = buf.getLong(0x18)
    
    // 2. 读索引表
    val entries = (0 until numEntries).map { i ->
        val base = indexOffset.toInt() + i * 48
        val fileOffset = buf.getLong(base)
        val fileSize = buf.getInt(base + 16)
        fileOffset to fileSize
    }
    
    // 3. 读文件名表
    val nameEnd = data.indexOf(0.toByte(), nameOffset.toInt())
    val names = String(data, nameOffset.toInt(), nameEnd - nameOffset.toInt())
        .split("\n").filter { it.isNotBlank() }
    
    // 4. 按名字提取文件
    return entries.zip(names).associate { (entry, name) ->
        val (offset, size) = entry
        name.trim() to data.copyOfRange(offset.toInt(), offset.toInt() + size)
    }
}

// 使用示例：
val files = parseZpk(zpkFile)
val metaJson = String(files["meta.json"]!!)    // 解析元数据
val audioBytes = files["us_ache_xxx.mp3"]!!    // 拿音频数据播放
```

---

## 6. zpk 文件定位

zpk 文件存储在 App 数据目录下：
```
baicizhan/zpack/409/{0..31}/zp_{topicId}_409_0_{timestamp}.zpk
```

`{0..31}` 是分片子目录，**子目录编号与 topicId 之间没有简单的数学映射关系**（不是取模、不是哈希），可能是服务端按下载批次分配的。

**推荐做法：App 启动时扫描一次所有子目录，建立 topicId → 文件路径 的内存映射表。**

```kotlin
// 扫描建立索引 (只需文件名, 不需要读文件内容, 很快)
fun buildZpkIndex(zpkRoot: File): Map<Int, File> {
    val index = mutableMapOf<Int, File>()
    zpkRoot.listFiles()?.forEach { subDir ->
        subDir.listFiles { f -> f.extension == "zpk" }?.forEach { zpk ->
            // zp_{topicId}_409_0_{timestamp}.zpk
            val topicId = zpk.name.split("_").getOrNull(1)?.toIntOrNull()
            if (topicId != null) index[topicId] = zpk
        }
    }
    return index
}
```

约 4000 个文件，扫描一次约 50-100ms。
