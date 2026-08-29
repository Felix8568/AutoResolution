package top.loongway.autoresolution.trackpad.a11y

import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import rikka.shizuku.Shizuku
import top.loongway.autoresolution.ResolutionManager

/**
 * 无障碍服务生命周期保障（强制依赖 Shizuku，自动授权）：
 * 1. 未启用 → 通过 Shizuku 直接写入 enabled_accessibility_services
 *    （等价于用户在系统设置中打开，无需手动操作）；
 * 2. 已启用但未绑定（force-stop/重装后 AMS 不重绑）→ 移除再恢复，强制重绑。
 * Shizuku 不可用时服务保持未开启，界面状态行提示。
 */
object AccessibilityRepair {

    private const val TAG = "A11yRepair"
    private const val COMPONENT =
        "top.loongway.autoresolution/top.loongway.autoresolution.trackpad.a11y.CursorAccessibilityService"

    @Volatile private var lastAttempt = 0L
    private val retryHandler = Handler(Looper.getMainLooper())

    /** Shizuku 可用（能代为授权） */
    fun canAutoEnable(): Boolean = try {
        Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Exception) {
        false
    }

    /** 确保服务已启用且已绑定（幂等；10 秒节流）。入口：应用启动 / 回前台 */
    fun ensure() {
        if (CursorAccessibilityService.isAvailable()) return
        if (!canAutoEnable()) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastAttempt < 10_000) return
        lastAttempt = now
        ResolutionManager.execShellAsync("settings get secure enabled_accessibility_services") { raw ->
            handleSettings(raw)
        }
    }

    /** 用户点击立即执行（绕过 10 秒节流） */
    fun enableNow() {
        lastAttempt = 0
        ensure()
    }

    private fun handleSettings(raw: String) {
        if (raw.startsWith("error")) {
            // shell 服务尚未连上（懒绑定进行中）：3 秒后静默重试
            retryHandler.postDelayed({ lastAttempt = 0; ensure() }, 3_000)
            return
        }
        val current = raw.lines()
            .firstOrNull { !it.startsWith("exit=") && it.isNotBlank() }
            ?.trim()
            .takeUnless { it == "null" }
            .orEmpty()
        val services = current.split(":").filter { it.isNotBlank() }

        if (!services.contains(COMPONENT)) {
            // ① 未启用 → 追加并打开总开关（Shizuku 自动授权）
            Log.d(TAG, "enabling accessibility service via shizuku")
            val newList = (services + COMPONENT).joinToString(":")
            ResolutionManager.execShellAsync(
                "settings put secure enabled_accessibility_services \"$newList\""
            )
            ResolutionManager.execShellAsync("sleep 1; settings put secure accessibility_enabled 1")
            return
        }

        // ② 已启用但未绑定（force-stop/重装后 AMS 不重绑）→ 移除再恢复，强制重绑
        Log.d(TAG, "service enabled but unbound, re-binding via settings rewrite")
        val without = services.filter { it != COMPONENT }.joinToString(":")
        ResolutionManager.execShellAsync(
            "settings put secure enabled_accessibility_services \"$without\""
        )
        ResolutionManager.execShellAsync(
            "sleep 1; settings put secure enabled_accessibility_services \"$current\"; " +
                "settings put secure accessibility_enabled 1"
        )
    }
}
