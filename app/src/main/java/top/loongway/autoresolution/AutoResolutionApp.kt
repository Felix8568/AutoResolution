package top.loongway.autoresolution

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import rikka.shizuku.Shizuku
import top.loongway.autoresolution.trackpad.a11y.AccessibilityRepair
import top.loongway.autoresolution.trackpad.cursor.SecondaryDisplayController
import top.loongway.autoresolution.trackpad.inject.InputInjector

/** Application 入口：Shizuku binder 就绪且权限齐全时自动启动服务 */
class AutoResolutionApp : Application() {

    override fun onCreate() {
        super.onCreate()

        ResolutionManager.init(this)
        InputInjector.init(this)
        // 副屏触点控制器：随应用常驻，监听外接屏热插拔
        SecondaryDisplayController.start(this)
        createNotificationChannel()

        Shizuku.addBinderReceivedListenerSticky { tryAutoStart() }
    }

    fun tryAutoStart() {
        try {
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return
            if (!Shizuku.pingBinder()) return
        } catch (_: IllegalStateException) {
            // Shizuku binder 还没有准备好，监听者会在后续重试
            return
        }
        // 先自动授予缺失权限（幂等），再判断是否齐全，齐全才启动服务
        ResolutionManager.ensureShizukuPermissions {
            if (!PermissionChecker.check(this).allGranted) return@ensureShizukuPermissions
            ResolutionManager.rebind()
            InputInjector.bind() // 绑定触摸事件注入服务
            // 无障碍触点服务：未启用自动授权、掉绑自动重绑（全部经 Shizuku）
            AccessibilityRepair.ensure()
            DisplayMonitorService.start(this)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(
                DisplayMonitorService.CHANNEL_ID,
                getString(R.string.app_name),
                NotificationManager.IMPORTANCE_HIGH
            )
        )
    }
}
