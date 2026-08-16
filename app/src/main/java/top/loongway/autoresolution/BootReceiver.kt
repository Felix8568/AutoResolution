package top.loongway.autoresolution

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** 开机自启：收到 BOOT_COMPLETED 广播且权限齐全时启动前台服务 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // 权限齐全才启动服务；缺失时由用户打开 App 修复后自动启动
        if (!PermissionChecker.check(context).allGranted) {
            Log.w(TAG, "BOOT_COMPLETED — permissions not ready, service not started")
            return
        }
        Log.d(TAG, "BOOT_COMPLETED — starting monitor service")
        DisplayMonitorService.start(context)
    }
}