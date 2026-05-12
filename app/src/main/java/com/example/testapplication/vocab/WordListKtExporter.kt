package com.example.testapplication.vocab

import android.content.Context
import android.os.Environment
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * 按 [UnmasteredWords35.kt] 风格导出「未斩 / 已斩」单词列表为 `.kt` 注释文档（纯文本，非编译用）。
 *
 * - 掌握判定与列表页一致：[WordRepository.effectiveMastered] + MMKV overrides。
 * - **词表范围**与 [WordListScreen] 一致：读取 `cross_book_filter`，开启时排除更高优先级词书中出现的 topicId
 *   （优先级即 [WordBook] 枚举顺序：中考 > 高考 > 四级 > 六级 > 雅思）；关闭时导出该书全部词。
 *
 * 导出过程中会多次 [WordRepository.loadWords]；结束后会根据主界面持久化的词书选择恢复当前加载的词书，
 * 避免返回单词列表时「芯片显示 A、列表却是最后一本导出词书」的不一致。
 */
object WordListKtExporter {

    private const val WORD_LIST_PREFS_ID = "word_list_prefs"
    private const val KEY_SELECTED_BOOK_ID = "selected_book_id"
    /** 与 [WordListActivity] 中一致 */
    private const val KEY_CROSS_BOOK_FILTER = "cross_book_filter"

    /** 四本考试词书（不含雅思） */
    val EXPORT_BOOKS = listOf(
        WordBook.ZHONGKAO,
        WordBook.GAOKAO,
        WordBook.CET4,
        WordBook.CET6,
    )

    private const val WORD_COLUMN_WIDTH = 20

    private fun sanitizeForComment(s: String): String =
        s.replace("\r\n", " ")
            .replace('\r', ' ')
            .replace('\n', ' ')
            .replace("*/", "* /")
            .trim()

    fun buildDocument(
        book: WordBook,
        kindLabel: String,
        words: List<Word>,
        crossBookFilterApplied: Boolean,
    ): String {
        val sb = StringBuilder()
        val n = words.size
        val scopeNote = if (crossBookFilterApplied) {
            "（与列表「过滤」开：已排除高优先级词书中出现的词）"
        } else {
            "（与列表「过滤」关：本书全部词）"
        }
        sb.append("// 百词斩 · $kindLabel  共 $n 个  ${book.displayName} $scopeNote\n")
        sb.append("//\n")
        sb.append("//                     \n")
        sb.append("// ══════════════════════════════════════\n")
        sb.append("// 📖 ${book.displayName}词汇（$n 词）\n")
        sb.append("// ══════════════════════════════════════\n")
        sb.append("//\n")

        for (w in words) {
            val line = formatWordLine(w.word, w.meanCn)
            sb.append("// ").append(line).append('\n')
        }
        return sb.toString()
    }

    private fun formatWordLine(word: String, meanCn: String): String {
        val w = sanitizeForComment(word)
        val m = sanitizeForComment(meanCn)
        return if (w.length <= WORD_COLUMN_WIDTH) {
            w.padEnd(WORD_COLUMN_WIDTH) + m
        } else {
            "$w $m"
        }
    }

    /**
     * 与主界面 [WordListActivity] 一致：恢复为用户选中的词书，保证 singleton Repository 与列表 UI 一致。
     */
    private suspend fun restoreUserSelectedBook(repository: WordRepository, context: Context) {
        val prefs = MMKV.mmkvWithID(WORD_LIST_PREFS_ID)
        val savedId = prefs.decodeInt(KEY_SELECTED_BOOK_ID, WordBook.ZHONGKAO.id)
        val book = WordBook.entries.firstOrNull { it.id == savedId } ?: WordBook.ZHONGKAO
        repository.loadWords(book)
    }

    /**
     * 与 [WordListScreen] 中 `excludedTopicIds` 相同：更高优先级词书的 topicId 并集。
     */
    private fun excludedTopicIdsForBook(
        book: WordBook,
        crossBookFilterEnabled: Boolean,
        bookTopicIds: Map<WordBook, Set<Int>>,
    ): Set<Int> {
        if (!crossBookFilterEnabled) return emptySet()
        return WordBook.entries
            .takeWhile { it != book }
            .flatMap { bookTopicIds[it] ?: emptySet() }
            .toSet()
    }

    /**
     * 依次加载各词书并写入 8 个文件（每词书未斩 + 已斩）。
     * 无论成功或部分失败，都会尝试恢复用户当前词书选择。
     *
     * @return 写入目录的绝对路径；任一导出步骤失败时 [Result.failure] 携带说明。
     */
    suspend fun exportToDocuments(context: Context, repository: WordRepository): Result<String> =
        withContext(Dispatchers.IO) {
            val overrides = repository.overrides.value
            val root = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                ?: return@withContext Result.failure(Exception("无法访问应用文档目录"))
            val outDir = File(root, "word_export_kotlin")
            if (!outDir.exists() && !outDir.mkdirs()) {
                return@withContext Result.failure(Exception("无法创建目录: ${outDir.absolutePath}"))
            }

            val errors = mutableListOf<String>()
            val listPrefs = MMKV.mmkvWithID(WORD_LIST_PREFS_ID)
            val crossBookFilterEnabled = listPrefs.decodeBool(KEY_CROSS_BOOK_FILTER, false)

            try {
                for (book in EXPORT_BOOKS) {
                    repository.loadWords(book)
                    when (val st = repository.loadState.value) {
                        is LoadState.Success -> {
                            val all = st.words
                            val excluded = excludedTopicIdsForBook(
                                book,
                                crossBookFilterEnabled,
                                repository.bookTopicIds.value,
                            )
                            val visible = all.filter { it.topicId !in excluded }
                            val unmastered = visible.filter {
                                !repository.effectiveMastered(it.topicId, it.masteredInDb, overrides)
                            }
                            val mastered = visible.filter {
                                repository.effectiveMastered(it.topicId, it.masteredInDb, overrides)
                            }

                            val unBody = buildDocument(
                                book, "未斩单词", unmastered, crossBookFilterEnabled,
                            )
                            val msBody = buildDocument(
                                book, "已斩单词", mastered, crossBookFilterEnabled,
                            )

                            val unFile = File(outDir, "UnmasteredWords_${book.id}.kt")
                            val msFile = File(outDir, "MasteredWords_${book.id}.kt")
                            try {
                                unFile.writeText(unBody, StandardCharsets.UTF_8)
                                msFile.writeText(msBody, StandardCharsets.UTF_8)
                            } catch (e: Exception) {
                                errors.add("${book.displayName}: ${e.message}")
                            }
                        }

                        is LoadState.Error -> errors.add("${book.displayName}: ${st.message}")
                        else -> errors.add("${book.displayName}: 加载状态异常")
                    }
                }

                if (errors.isNotEmpty()) {
                    Result.failure(Exception(errors.joinToString("\n")))
                } else {
                    Result.success(outDir.absolutePath)
                }
            } finally {
                restoreUserSelectedBook(repository, context)
            }
        }
}
