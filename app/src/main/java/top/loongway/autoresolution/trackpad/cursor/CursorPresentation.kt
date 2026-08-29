package top.loongway.autoresolution.trackpad.cursor

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Display
import android.view.WindowManager

/**
 * 副屏 Presentation：全屏透明窗口，唯一内容是 [CursorView] 触点。
 *
 * 两个关键点：
 * 1. 背景全透明 —— 副屏上真正显示内容的 App 依然可见、可交互；
 * 2. FLAG_NOT_TOUCHABLE —— 窗口不消费触摸，否则注入的点击/滚动会被
 *    本窗口吃掉，出现“触点能动、副屏 App 毫无反应”的现象。
 */
class CursorPresentation(outerContext: Context, display: Display) :
    Presentation(outerContext, display) {

    lateinit var cursorView: CursorView
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 用 Presentation 自己的 context（携带目标 display 信息）创建触点
        cursorView = CursorView(context)
        setContentView(cursorView)
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setFlags(
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            )
        }
    }
}
