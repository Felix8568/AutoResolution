package top.loongway.autoresolution.shizuku.service

import android.util.Log
import top.loongway.autoresolution.shizuku.IShellService
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.system.exitProcess

/**
 * 通用 Shell 命令执行器（Shizuku UserService）。
 * 在 shell/root 权限进程里跑 [exec]，用于 wm size、dumpsys 等需要提权的命令。
 * App 进程通过 [IShellService] AIDL 接口远程调用。
 */
class ShellService : IShellService.Stub() {

    override fun exec(command: String): String {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val out = BufferedReader(InputStreamReader(p.inputStream)).readText()
            val err = BufferedReader(InputStreamReader(p.errorStream)).readText()
            p.waitFor()
            "exit=${p.exitValue()}\n$out\n$err".trim()
        } catch (e: Exception) {
            Log.e("ShellService", "exec failed: $command", e)
            "error: ${e.message}"
        }
    }

    override fun destroy() {
        Log.d("ShellService", "destroy requested, exiting")
        exitProcess(0)
    }
}
