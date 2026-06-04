package com.example.testapplication.desktop

import java.io.File

/**
 * Mac 版数据路径。
 * - assetsDir：只读，指向当前项目的 assets/baicizhan（DB + zpack）
 * - dataDir：可写，存放 Mac 版自己产生的数据（进度/偏好/斩状态），默认在仓库根的 mac_data/
 *
 * 优先用系统属性覆盖：-Dvocab.assets=... / -Dvocab.data=...
 * 否则从工作目录向上逐级查找仓库内的 assets 路径（开发期 run 时稳健）。
 */
object DesktopPaths {

    private val resolvedRoot: File? = findRepoRoot()

    val assetsDir: File = run {
        System.getProperty("vocab.assets")?.let { return@run File(it) }
        resolvedRoot?.let { File(it, "app/src/main/assets/baicizhan") }
            ?: File("app/src/main/assets/baicizhan")
    }

    val dataDir: File = run {
        System.getProperty("vocab.data")?.let { return@run File(it).apply { mkdirs() } }
        val base = resolvedRoot ?: File(System.getProperty("user.dir"))
        File(base, "mac_data").apply { mkdirs() }
    }

    val lookupDb: File get() = File(assetsDir, "lookup.db")
    val statusDb: File get() = File(assetsDir, "baicizhantopicproblem.db")
    val zpackDir: File get() = File(assetsDir, "zpack")
    fun roadmap(book: com.example.testapplication.vocab.WordBook): File =
        File(assetsDir, "roadmap/${book.roadmapFileName}")

    /** 从工作目录向上找含 app/src/main/assets/baicizhan 的仓库根 */
    private fun findRepoRoot(): File? {
        var dir: File? = File(System.getProperty("user.dir"))
        repeat(6) {
            val d = dir ?: return null
            if (File(d, "app/src/main/assets/baicizhan").exists()) return d
            dir = d.parentFile
        }
        return null
    }
}
