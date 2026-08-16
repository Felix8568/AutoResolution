package top.loongway.autoresolution

import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import rikka.shizuku.Shizuku

/** 应用运行所需的权限清单；所有操作在权限齐全（[allGranted]）后才执行 */
data class PermissionStatus(
    val shizuku: Boolean,
    val notification: Boolean,
    val batteryIgnored: Boolean,
) {
    val allGranted: Boolean get() = shizuku && notification && batteryIgnored
}

object PermissionChecker {

    fun check(context: Context): PermissionStatus {
        val pkg = context.packageName
        val nm = context.getSystemService(NotificationManager::class.java)
        val pm = context.getSystemService(PowerManager::class.java)
        return PermissionStatus(
            shizuku = try {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            } catch (_: IllegalStateException) {
                false // Shizuku binder 未就绪
            },
            notification = nm.areNotificationsEnabled(),
            batteryIgnored = pm.isIgnoringBatteryOptimizations(pkg),
        )
    }
}
