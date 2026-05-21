# ZPK 数据结构分析与优化方案

> 分析日期：2026-05-19
> 分析范围：`app/src/main/assets/baicizhan/zpack/` 下全部 zpk 文件

---

## 1. 数据总览

| 指标 | 数值 |
|------|------|
| zpk 文件总数 | 23,949 |
| 唯一 topicId 数 | 10,017 |
| 总大小 | ~4,468 MB（~4.5 GB） |
| 单文件平均大小 | ~190 KB |
| 单文件最小 | ~15 KB |
| 单文件最大 | ~815 KB |
| 单文件中位数 | ~186 KB |
| 每个 zpk 平均子文件数 | 7 个 |

---

## 2. ZPAK0300 格式结构

```
偏移      字段              说明
0x00      magic             "ZPAK0300"（8 字节 ASCII）
0x0C      numEntries        子文件数量（uint32 LE）
0x10      indexOffset        索引表偏移（uint64 LE）
0x18      nameOffset         文件名表偏移（uint64 LE）
0x80+     [数据区]           各子文件原始字节，无加密无压缩

索引表（每条 48 字节）：
  +0   dataOffset   uint64   子文件在数据区的偏移
  +16  dataSize     uint32   子文件大小

文件名表：
  '\n' 分隔，'\0' 终止，与索引表顺序一一对应
```

**特点：无加密、无压缩。** 解析开销极低，`copyOfRange` 即可提取子文件。

---

## 3. 单个 zpk 内容构成

以 `zp_8742_410_0_20250917165229.zpk`（225 KB）为例：

```
meta.json                          803 B    元数据（音标/释义/音频路径）
resource.json                    2,279 B    百词斩原始资源配置
wiki.json                          159 B    百词斩 wiki 数据
us_symbolize_*.mp3              24,000 B    美音单词发音
uk_symbolize_*.mp3              24,000 B    英音单词发音（App fallback）
us_These_three_*.mp3            81,120 B    例句音频 1
us_For_Chinas_*.mp3             92,160 B    例句音频 2
```

### 500 个样本统计的各类文件占比

| 文件类型 | 占比 | 推算总量 | App 是否使用 |
|----------|------|----------|-------------|
| 例句音频（us_Sentence*.mp3） | 47.4% | ~2,144 MB | ✅ 闪卡长按播放 |
| 其他音频（hash名.mp3/aac） | 29.3% | ~1,323 MB | ✅ 多数是 meta 引用的 word_audio/sentence_audio |
| 单词发音（uk_/us_word.mp3） | 21.4% | ~968 MB | ✅ 列表朗读、闪卡朗读 |
| resource.json | 1.1% | ~50 MB | ❌ **未使用** |
| meta.json | 0.4% | ~18 MB | ✅ 核心元数据 |
| ZPAK 头+索引开销 | 0.4% | ~16 MB | 结构开销 |
| wiki.json | 0.1% | ~4 MB | ❌ **未使用** |

### App 实际使用的子文件

根据 `FlashCardActivity.kt`、`WordListActivity.kt`、`ZpkMeta.kt` 代码分析：

1. **meta.json** — 解析 `word_audio`、`sentence_audio` 路径，以及 `accent`/`meanCn`/`meanEn`/`sentence`/`sentenceTrans`
2. **word_audio 指向的 mp3** — 单词朗读（列表点击 + 闪卡自动播放）
3. **uk_*.mp3** — word_audio 缺失时的 fallback
4. **sentence_audio 指向的 mp3** — 闪卡长按例句时播放

**以下文件完全未被 App 使用：**
- `resource.json` — 百词斩 App 原始资源映射
- `wiki.json` — 百词斩 wiki 补充数据
- **多余的例句音频** — 每个 zpk 含 2-4 条例句 mp3，但 App 只用 `meta.sentence_audio` 指向的那 1 条

---

## 4. 跨词书重复分析

5 本词书（中考/高考/四级/六级/雅思）的单词大量重叠：

| topicId 出现词书数 | 单词数 |
|-------------------|--------|
| 仅 1 本 | 3,576 |
| 2 本 | 1,897 |
| 3 本 | 1,599 |
| 4 本 | 2,943 |
| 5 本 | 2 |

**重复文件统计：**
- 跨词书重复的 zpk 文件：**13,932 个**
- 重复文件占用空间：**~2,754 MB（占总量 61.6%）**

**但不能简单删除重复文件**，因为同一 topicId 在不同词书中的 zpk 内容有差异：
- `meta.json` 不同（不同词书配不同例句）
- `sentence_audio` 不同（例句音频对应不同例句）
- `uk_word.mp3` / `us_word.mp3` **完全相同**（单词发音不变）

---

## 5. 音频编码情况

抽样检测 mp3 编码参数：

| 采样 | 格式 | 码率 | 采样率 | 声道 |
|------|------|------|--------|------|
| 例句音频 | MPEG Layer III v2 | 160 kbps | 24 kHz | Mono |
| hash 名音频 | MPEG Layer III v1 | 80 kbps | 32 kHz | Joint Stereo |
| hash 名音频 | MPEG Layer III v2 | 48 kbps | 22.05 kHz | Mono |

码率分布不均匀（48~160 kbps），部分例句音频码率偏高，有压缩空间。

---

## 6. 优化方案

### 方案 A：剥离未使用文件（推荐优先执行）

**原理**：重写 zpk，只保留 App 实际使用的子文件。

**保留：**
- `meta.json`
- `meta.word_audio` 指向的 mp3
- `meta.sentence_audio` 指向的 mp3
- `uk_*.mp3`（word_audio 缺失时的 fallback）

**删除：**
- `resource.json`
- `wiki.json`
- 未被 meta 引用的多余例句 mp3

**预估收益：**
- 节省 ~2,100 MB（45%）
- 剩余 ~2,400 MB

**实施方式：** 编写 Python 脚本（类似已有的 `zpk_strip_images.py`），遍历所有 zpk 重新打包。

**对 App 代码的影响：无。** 解析逻辑不变，只是 zpk 里的文件变少了。

---

### 方案 B：跨词书去重（结构改造）

**原理**：同一 topicId 的单词发音（uk_/us_word.mp3）在所有词书中完全相同，只需保留一份。

**策略 B1 — 共享单词发音，独立例句音频：**
- 将 zpk 拆分为「单词发音包」（按 topicId 去重）+「例句音频包」（按 bookId 保留）
- 需要修改 `WordRepository.readZpk()` 的索引和读取逻辑

**策略 B2 — 每个 topicId 只保留一个词书的 zpk：**
- 按词书优先级（中考 > 高考 > 四级 > 六级 > 雅思）只留最高优先级版本
- 其他词书的同一单词共享该 zpk（例句可能不匹配，但影响有限）

**预估收益：**
- 在方案 A 基础上再省 ~1,200 MB
- 剩余 ~1,200 MB

**对 App 代码的影响：** 需修改 `buildZpkIndex()` 逻辑（策略 B2 较简单）。

---

### 方案 C：音频转码降码率

**原理**：语音内容不需要高码率，统一降码率可显著减小体积。

**建议参数：**
- 单词发音：**64 kbps**，22.05 kHz，Mono
- 例句音频：**80 kbps**，22.05 kHz，Mono

**预估收益：** 在方案 A/B 基础上再压缩 30-40%。

**实施方式：** `ffmpeg -i input.mp3 -b:a 64k -ar 22050 -ac 1 output.mp3`，集成到剥离脚本中。

**对 App 代码的影响：无。**

---

## 7. 综合收益对比

| 方案组合 | 节省 | 剩余 | App 代码改动 | 实施难度 |
|----------|------|------|-------------|---------|
| A（剥离未用文件） | ~2.1 GB | ~2.4 GB | 无 | ★☆☆ 低 |
| A + B2（+ 跨词书去重） | ~3.3 GB | ~1.2 GB | 小（索引逻辑） | ★★☆ 中 |
| A + C（+ 音频转码） | ~3.1 GB | ~1.4 GB | 无 | ★★☆ 中 |
| A + B2 + C（全部） | ~4.0 GB | ~0.5 GB | 小 | ★★☆ 中 |

---

## 8. 推荐执行顺序

1. **第一步：方案 A**（剥离未用文件）— 投入产出比最高，零代码改动，写脚本即可
2. **第二步：方案 C**（音频转码）— 可集成到方案 A 的脚本中，一起跑
3. **第三步：方案 B2**（跨词书去重）— 需小幅改动 App 代码，评估例句不匹配的影响后再决定

---

## 附录：现有预处理脚本

- `assets/baicizhan/zpk_strip_images.py` — 已执行，剥离了 zpk 中的图片文件（.jpg/.jpeg/.png/.gif/.webp/.bmp）
- 新的剥离脚本可基于此扩展，增加 resource.json/wiki.json/多余音频的剥离逻辑
