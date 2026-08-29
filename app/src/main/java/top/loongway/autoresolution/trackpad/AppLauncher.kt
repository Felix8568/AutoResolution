package top.loongway.autoresolution.trackpad

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Log
import top.loongway.autoresolution.ResolutionManager

/**
 * 副屏应用启动器：查询启动器应用，并把目标应用“跨屏”启动到副屏。
 *
 * 双路线（优先级从高到低）：
 * 1. Shizuku shell：`am start --display <id>`，shell 身份直接拉起，
 *    绕过 ROM 的跨屏启动确认弹窗；
 * 2. 框架路线：ActivityOptions.setLaunchDisplayId（API 28+），
 *    无需 Shizuku，但部分 ROM 会弹「是否允许打开」确认框。
 */
object AppLauncher {

    private const val TAG = "AppLauncher"

    /** 启动器条目 */
    data class AppInfo(
        val label: String,
        val icon: Drawable,
        val packageName: String,
        val activityName: String,
    )

    /**
     * 查询所有带启动器入口的应用（过滤自身），按名称排序。
     * 依赖 Manifest 中的 <queries> 声明，否则 Android 11+ 查询结果为空。
     */
    fun queryLaunchable(context: Context): List<AppInfo> {
        val pm = context.packageManager
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(query, 0)
            .asSequence()
            .filter { it.activityInfo.packageName != context.packageName }
            .map {
                AppInfo(
                    label = it.loadLabel(pm).toString(),
                    icon = it.loadIcon(pm),
                    packageName = it.activityInfo.packageName,
                    activityName = it.activityInfo.name,
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    /** 在 [displayId] 对应的副屏上启动应用 */
    fun launchOnDisplay(context: Context, app: AppInfo, displayId: Int) {
        val component = ComponentName(app.packageName, app.activityName)
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(app.packageName, app.activityName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /**
         * ① 优先 Shizuku shell 路线（am start --display）：
         * 以 shell 身份直接拉起目标应用，绕过 ROM 对第三方应用跨屏启动的
         * 「是否允许打开」确认弹窗（该弹窗会抢占焦点、中断触控板状态）。
         */
        ResolutionManager.execShellAsync(
            "am start --display $displayId -n ${component.flattenToShortString()}"
        ) { result ->
            if (result.startsWith("exit=0")) return@execShellAsync
            Log.w(TAG, "shizuku am start failed: $result")
            launchViaFramework(context, intent, displayId)
        }
    }

    /** ② 降级：框架跨屏路线（无需 Shizuku，但部分 ROM 会弹确认框） */
    private fun launchViaFramework(context: Context, intent: Intent, displayId: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        try {
            val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
            context.startActivity(intent, options.toBundle())
        } catch (e: Exception) {
            Log.e(TAG, "framework cross-display launch failed", e)
        }
    }
}
