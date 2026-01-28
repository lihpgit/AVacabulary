package com.example.testapplication

import java.io.DataOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader

object RootUtils {

    /**
     * 执行 Root 命令
     * @param command Shell 命令
     * @return 命令输出结果
     */
    fun execRootCmd(command: String): String {
        var result = ""
        var process: Process? = null
        var os: DataOutputStream? = null
        var isReader: BufferedReader? = null

        try {
            // 请求 Root 权限并执行命令
            val process = Runtime.getRuntime().exec(arrayOf("su", "-mm"))
            val os = DataOutputStream(process.outputStream)

            // 写入命令
            os.writeBytes(command + "\n")
            os.writeBytes("exit\n")
            os.flush()

            // 读取输出
            isReader = BufferedReader(InputStreamReader(process.inputStream))
            val sb = StringBuilder()
            var line: String?
            while (isReader.readLine().also { line = it } != null) {
                sb.append(line).append("\n")
            }
            result = sb.toString()
            
            process.waitFor()
        } catch (e: Exception) {
            e.printStackTrace()
            result = "Error: ${e.message}"
        } finally {
            try {
                os?.close()
                isReader?.close()
                process?.destroy()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return result
    }

    /**
     * 检查是否拥有 Root 权限
     */
    fun isRooted(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)
            os.writeBytes("exit\n")
            os.flush()
            val exitValue = process.waitFor()
            exitValue == 0
        } catch (e: Exception) {
            false
        }
    }
}
