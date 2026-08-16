package top.loongway.autoresolution

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import rikka.shizuku.Shizuku

/**
 * Application 入口。Shizuku binder 就绪时自动启动前台服务，无需用户手动操作。
 */
class AutoResolutionApp : Application() {

    override fun onCreate() {
        super.onCreate()

        ResolutionManager.init(this)
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
        ResolutionManager.rebind()
        DisplayMonitorService.start(this)
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
