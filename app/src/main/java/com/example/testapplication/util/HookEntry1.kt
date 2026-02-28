package com.example.testapplication.util


import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class HookEntry1 : IXposedHookLoadPackage {

    // TODO: 替换为你的目标 App 包名
    private val targetPkg = "com.jiongji.andriod.card"
    // 先用命令查到真正桌面包名后填这里：比如 com.android.launcher3 / com.miui.home ...
    private val launcherPkg = "com.android.launcher3"

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        when (lpparam.packageName) {
            targetPkg -> {
                log("=== Loaded target: ${lpparam.packageName} ===")
                hookSharedPreferences(lpparam)
                hookSqlite(lpparam)
                hookProviderQuery(lpparam)
            }
            launcherPkg -> {
                log("=== Loaded launcher: ${lpparam.packageName} ===")
                hookLauncherClick(lpparam)
            }
            else -> return
        }
    }

    private fun hookSharedPreferences(lpparam: XC_LoadPackage.LoadPackageParam) {
        // SharedPreferencesImpl 是系统实现类
        runCatching {
            val spImplClass = XposedHelpers.findClass(
                "android.app.SharedPreferencesImpl",
                lpparam.classLoader
            )

            // getInt(String key, int defValue)
            XposedHelpers.findAndHookMethod(
                spImplClass,
                "getInt",
                String::class.java,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val key = param.args[0] as? String ?: return
                        val value = param.result as? Int ?: return

                        // 只打印可能相关的 key，避免刷屏（你也可以去掉过滤）
                        if (isInterestingKey(key) || value == 1765) {
                            log("[SP] getInt key=$key => $value")
                            if (value == 1765) {
                                logStack("SP hit 1765", param)
                            }
                        }
                    }
                }
            )

            // 如果你怀疑存的是 Long / String，也可加：
            // getLong / getString 等
            XposedHelpers.findAndHookMethod(
                spImplClass,
                "getLong",
                String::class.java,
                Long::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val key = param.args[0] as? String ?: return
                        val value = param.result as? Long ?: return
                        if (isInterestingKey(key) || value == 1765L) {
                            log("[SP] getLong key=$key => $value")
                            if (value == 1765L) logStack("SP hit 1765L", param)
                        }
                    }
                }
            )

            log("SharedPreferences hooks installed.")
        }.onFailure {
            log("SharedPreferences hook failed: ${it.message}")
        }
    }

    private fun hookSqlite(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            // 1) Hook rawQuery: SQLiteDatabase.rawQuery(String sql, String[] selectionArgs)
            XposedHelpers.findAndHookMethod(
                SQLiteDatabase::class.java,
                "rawQuery",
                String::class.java,
                Array<String>::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val sql = (param.args[0] as? String) ?: return
                        if (!shouldLogSql(sql)) return
                        log("[SQL] rawQuery => $sql")
                        logStack("rawQuery stack", param)
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        val sql = (param.args[0] as? String) ?: return
                        if (!looksLikeCount(sql)) return
                        val cursor = param.result as? android.database.Cursor ?: return
                        runCatching {
                            val oldPos = cursor.position
                            if (cursor.moveToFirst()) {
                                val v = cursor.getLong(0)
                                if (v == 1765L) {
                                    log("[SQL] COUNT result => $v (HIT 1765) | sql=$sql")
                                    logStack("COUNT hit stack", param)
                                } else if (v > 0) {
                                    // Keep a lightweight signal for other COUNTs
                                    log("[SQL] COUNT result => $v | sql=$sql")
                                }
                            }
                            if (oldPos >= -1) cursor.moveToPosition(oldPos)
                        }
                    }
                }
            )

            // 2) Hook simpleQueryForLong：很多 count(*) 会走这里
            XposedHelpers.findAndHookMethod(
                SQLiteStatement::class.java,
                "simpleQueryForLong",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val res = (param.result as? Long) ?: return
                        // 可能 count 就是 1765
                        if (res == 1765L) {
                            log("[SQL] simpleQueryForLong => $res (HIT 1765)")
                            logStack("simpleQueryForLong stack", param)
                        }
                    }
                }
            )

            log("SQLite hooks installed.")
        }.onFailure {
            log("SQLite hook failed: ${it.message}")
        }
    }

    private fun hookProviderQuery(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val providerClassName = "com.baicizhan.client.business.dataset.provider.BaicizhanContentProvider"
            val providerCls = XposedHelpers.findClassIfExists(providerClassName, lpparam.classLoader)
                ?: run {
                    log("Provider class not found: $providerClassName")
                    return
                }

            // Legacy query: query(Uri, String[], String, String[], String)
            runCatching {
                XposedHelpers.findAndHookMethod(
                    providerCls,
                    "query",
                    Uri::class.java,
                    Array<String>::class.java,
                    String::class.java,
                    Array<String>::class.java,
                    String::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val uri = param.args[0] as? Uri
                            val projection = param.args[1] as? Array<String>
                            val selection = param.args[2] as? String
                            val selectionArgs = param.args[3] as? Array<String>
                            val sortOrder = param.args[4] as? String

                            if (!shouldLogProvider(uri, selection)) return

                            log(
                                "[CP] query uri=${uri} | projection=${projection?.contentToString()} | " +
                                    "selection=${selection} | args=${selectionArgs?.contentToString()} | sort=${sortOrder}"
                            )
                            logStack("CP query stack", param)
                        }

                        override fun afterHookedMethod(param: MethodHookParam) {
                            val uri = param.args[0] as? Uri
                            val selection = param.args[2] as? String
                            if (!shouldLogProvider(uri, selection)) return

                            val cursor = param.result as? Cursor ?: return
                            runCatching {
                                val colCount = cursor.columnCount
                                val cols = cursor.columnNames?.joinToString(",")
                                val oldPos = cursor.position

                                // Light-touch peek: if it's a single-cell result (often COUNT), log its value.
                                var peek: String? = null
                                if (cursor.moveToFirst() && colCount == 1) {
                                    peek = runCatching { cursor.getLong(0).toString() }.getOrElse {
                                        runCatching { cursor.getString(0) }.getOrNull()
                                    }
                                }

                                if (oldPos >= -1) cursor.moveToPosition(oldPos)

                                if (peek != null) {
                                    log("[CP] query result peek=$peek | cols=$cols | uri=$uri")
                                    if (peek == "1765") {
                                        log("[CP] HIT 1765 via provider | uri=$uri | selection=$selection")
                                        logStack("CP HIT 1765 stack", param)
                                    }
                                } else {
                                    log("[CP] query result cols=$cols (colCount=$colCount) | uri=$uri")
                                }
                            }
                        }
                    }
                )
                log("ContentProvider legacy query hook installed.")
            }.onFailure {
                log("ContentProvider legacy query hook failed: ${it.message}")
            }

            // Newer query (API 26+): query(Uri, String[], Bundle, CancellationSignal)
            runCatching {
                XposedHelpers.findAndHookMethod(
                    providerCls,
                    "query",
                    Uri::class.java,
                    Array<String>::class.java,
                    Bundle::class.java,
                    CancellationSignal::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val uri = param.args[0] as? Uri
                            val projection = param.args[1] as? Array<String>
                            val bundle = param.args[2] as? Bundle

                            if (!shouldLogProvider(uri, bundle?.toString())) return

                            log(
                                "[CP] query26 uri=${uri} | projection=${projection?.contentToString()} | bundle=${bundle}"
                            )
                            logStack("CP query26 stack", param)
                        }

                        override fun afterHookedMethod(param: MethodHookParam) {
                            val uri = param.args[0] as? Uri
                            val cursor = param.result as? Cursor ?: return
                            runCatching {
                                val cols = cursor.columnNames?.joinToString(",")
                                log("[CP] query26 result cols=$cols | uri=$uri")
                            }
                        }
                    }
                )
                log("ContentProvider query26 hook installed.")
            }.onFailure {
                // Not all providers override this; ignore quietly but keep one log line.
                log("ContentProvider query26 not available: ${it.message}")
            }
        }.onFailure {
            log("hookProviderQuery failed: ${it.message}")
        }
    }

    private fun isInterestingKey(key: String): Boolean {
        val k = key.lowercase()
        return k.contains("learn") ||
                k.contains("known") ||
                k.contains("study") ||
                k.contains("progress") ||
                k.contains("count") ||
                k.contains("word")
    }

    private fun looksLikeCount(sql: String): Boolean {
        val s = sql.lowercase().trim()
        if (s.contains("sqlite_master")) return false
        return s.contains("count(") || s.startsWith("select count")
    }

    private fun looksLikeProgress(sql: String): Boolean {
        val s = sql.lowercase()
        // 你可以按目标 app 的表名再细化过滤
        return s.contains("word") || s.contains("vocab") || s.contains("progress") || s.contains("learn")
    }

    private fun shouldLogSql(sql: String): Boolean {
        val s = sql.lowercase()
        // Filter out noisy SDK/housekeeping queries
        if (s.contains("sqlite_master")) return false
        if (s.contains(" from look ")) return false
        // Keep queries likely related to vocabulary/progress
        return looksLikeCount(sql) || looksLikeProgress(sql)
    }

    private fun shouldLogProvider(uri: Uri?, selectionOrBundle: String?): Boolean {
        val u = uri?.toString()?.lowercase() ?: ""
        val s = selectionOrBundle?.lowercase() ?: ""

        // Filter obvious noise
        if (u.contains("getui")) return false
        if (s.contains(" from look ")) return false

        // Keep queries likely related to vocab/progress/offline/sync/book/user.
        return u.contains("word") || u.contains("vocab") || u.contains("topic") || u.contains("learn") ||
            u.contains("progress") || u.contains("book") || u.contains("resource") || u.contains("login") ||
            u.contains("offline") || u.contains("sync") ||
            s.contains("count") || s.contains("learn") || s.contains("word") || s.contains("topic") ||
            s.contains("progress") || s.contains("offline") || s.contains("sync")
    }

    private fun log(msg: String) {
        XposedBridge.log("XCountDemo: $msg")
    }

    private fun logStack(tag: String, param: XC_MethodHook.MethodHookParam) {
        val st = Throwable().stackTrace
            .take(15) // 不要太长
            .joinToString("\n") { "    at $it" }
        log("[$tag]\n$st")
    }


    private fun hookLauncherClick(lpparam: XC_LoadPackage.LoadPackageParam) {
        log("hookLauncherClick entered, pkg=${lpparam.packageName}")

        val candidateClasses = listOf(
            "com.android.launcher3.uioverrides.QuickstepLauncher",
            "com.android.launcher3.Launcher"
        )

        val classesToHook = candidateClasses
            .mapNotNull { name -> XposedHelpers.findClassIfExists(name, lpparam.classLoader) }
            .distinctBy { it.name }

        if (classesToHook.isEmpty()) {
            log("Launcher classes not found. Tried: ${candidateClasses.joinToString()}")
            return
        }

        classesToHook.forEach { cls ->
            log("Preparing hooks on ${cls.name}")

            val all = (cls.declaredMethods.asList() + cls.methods.asList())
                .filter { it.name == "startActivitySafely" }
                .distinctBy { m -> m.name + "(" + m.parameterTypes.joinToString(",") { it.name } + ")" }

            if (all.isEmpty()) {
                log("No startActivitySafely found on ${cls.name}")
                return@forEach
            }

            all.forEach { m ->
                runCatching {
                    XposedBridge.hookMethod(
                        m,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                val intent = param.args.firstOrNull { it is android.content.Intent } as? android.content.Intent
                                    ?: return
                                val ctx = (param.thisObject as? android.content.Context) ?: return

                                val pkg = intent.component?.packageName ?: intent.`package`
                                val label = pkg?.let { getAppLabel(ctx, it) }

                                // 每次点击都打日志，确认 hook 真的在跑
                                log("[Launcher] click => pkg=$pkg label=$label")

                                if (label != null && isAllEnglishLetters(label)) {
                                    android.widget.Toast.makeText(
                                        ctx,
                                        "应用名全英文：$label",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                    )
                    log("Hooked ${cls.simpleName}.startActivitySafely(${m.parameterTypes.joinToString { it.simpleName }})")
                }.onFailure {
                    log("Hook failed on ${cls.name}: ${it.message}")
                }
            }

            log("Installed hooks on ${cls.name}, total=${all.size}")
        }

        log("hookLauncherClick finished")
    }

    private fun getAppLabel(ctx: android.content.Context, pkg: String): String? {
        return runCatching {
            val pm = ctx.packageManager
            val ai = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(ai)?.toString()
        }.getOrNull()
    }

    private fun isAllEnglishLetters(label: String): Boolean {
        val s = label.trim()
        // “全是英文字母”——不含空格/数字/符号
        return s.isNotEmpty() && s.matches(Regex("^[A-Za-z]+$"))
    }

}

