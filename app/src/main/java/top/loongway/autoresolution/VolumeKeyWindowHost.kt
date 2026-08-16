package top.loongway.autoresolution

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
import android.view.WindowManager.LayoutParams.FLAG_SPLIT_TOUCH

/**
 * 音量键悬浮窗宿主。
 *
 * 在屏幕角落挂一个 1x1 透明窗口并持有系统输入焦点，
 * 通过 [View.OnKeyListener] 拦截音量键，用于开关屏幕电源。
 *
 * 为什么可行：
 * - [ResolutionManager.turnScreenOff] 走 SurfaceControl 只关闭显示输出、不触发系统休眠，
 *   输入系统仍认为屏幕亮着，黑屏状态下按键依然分发给本窗口；
 * - 窗口不设 FLAG_NOT_FOCUSABLE，成为系统焦点窗口后才能收到按键事件；
 * - FLAG_NOT_TOUCH_MODAL + FLAG_SPLIT_TOUCH 保证触摸穿透，不影响其他应用。
 *
 * 需要 SYSTEM_ALERT_WINDOW 权限；未授予时 [create] 会静默失败（addView 抛异常被吞掉）。
 */
class VolumeKeyWindowHost(
    private val context: Context,
    private val onKeyEvent: (keyCode: Int) -> Unit,
) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    @SuppressLint("InlinedApi")
    private val layoutParams = WindowManager.LayoutParams().apply {
        // TYPE_APPLICATION_OVERLAY 仅 API 26+ 存在；7.x 用老式 TYPE_SYSTEM_ALERT
        type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        format = PixelFormat.TRANSLUCENT
        gravity = Gravity.END or Gravity.BOTTOM
        width = 1
        height = 1
        flags = FLAG_NOT_TOUCH_MODAL or FLAG_SPLIT_TOUCH
    }

    private val view = View(context).apply {
        setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_UP &&
                (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
            ) {
                onKeyEvent(keyCode)
            }
            false // 放行给系统，音量调节功能不受影响
        }
    }

    @Volatile
    private var attached = false

    fun create() {
        if (attached) return
        try {
            windowManager.addView(view, layoutParams)
            attached = true
            Log.d(TAG, "volume key window attached")
        } catch (e: Exception) {
            Log.w(TAG, "addView failed, overlay permission missing?", e)
        }
    }

    fun destroy() {
        if (!attached) return
        try {
            windowManager.removeView(view)
        } catch (_: IllegalArgumentException) {
            // 窗口已被移除
        }
        attached = false
    }

    companion object {
        private const val TAG = "VolumeKeyWindowHost"
    }
}
