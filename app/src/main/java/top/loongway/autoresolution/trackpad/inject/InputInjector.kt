package top.loongway.autoresolution.trackpad.inject

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.view.MotionEvent
import rikka.shizuku.Shizuku
import rikka.shizuku.Shizuku.UserServiceArgs
import top.loongway.autoresolution.shizuku.IInputInjector
import top.loongway.autoresolution.shizuku.service.InputInjectorService

/**
 * Shizuku 反射注入路线的 App 进程入口：绑定 [InputInjectorService] 并转发事件。
 * 绑定方式与 [ResolutionManager] 完全一致（UserService + AIDL）。
 * 每次注入为一次 Binder 调用，延迟低，适合触点点击/滚轮这类需要跟手的操作。
 */
object InputInjector {

    private var appContext: Context? = null
    private var service: IInputInjector? = null
    private var args: UserServiceArgs? = null
    private var conn: ServiceConnection? = null

    /** 注入服务是否已就绪 */
    val isBound: Boolean get() = service != null

    fun init(application: Application) {
        appContext = application.applicationContext
        // Shizuku 重启/断连后清空代理，等待下次 bind 重建
        Shizuku.addBinderDeadListener { service = null }
    }

    /** 幂等绑定；未授权时静默跳过（授权流程走完后会再次调用） */
    fun bind() {
        val ctx = appContext ?: return
        if (service != null) return
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return

        // version 变更会让 Shizuku 杀掉旧服务进程、用新 APK 代码重建
        //（直接覆盖安装时旧进程会继续跑旧代码，必须提版本才生效）
        args = UserServiceArgs(
            ComponentName(ctx.packageName, InputInjectorService::class.java.name)
        ).tag("input-injector").processNameSuffix("input-injector").daemon(false).version(5)
        conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = IInputInjector.Stub.asInterface(binder)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
            }
        }
        Shizuku.bindUserService(args!!, conn!!)
    }

    /** 点击。失败（未绑定/注入异常）返回 false，调用方可降级 shell 路线 */
    fun tap(displayId: Int, x: Int, y: Int): Boolean {
        val s = service ?: return false
        return runCatching { s.injectTap(displayId, x, y) }.getOrDefault(false)
    }

    /** 滚轮：vTicks/hTicks，正数 = 内容向下/向右翻 */
    fun scroll(displayId: Int, x: Int, y: Int, vTicks: Float, hTicks: Float): Boolean {
        val s = service ?: return false
        return runCatching { s.injectScroll(displayId, x, y, vTicks, hTicks) }.getOrDefault(false)
    }

    /** 通用 MotionEvent 注入（事件所有权移交服务端，由其 recycle） */
    fun inject(event: MotionEvent, displayId: Int): Boolean {
        val s = service ?: return false
        return runCatching { s.injectMotionEvent(event, displayId) }.getOrDefault(false)
    }
}
