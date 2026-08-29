/**
 * 触摸事件注入接口。App 进程 → Shizuku 高权限进程（shell uid，具备 INJECT_EVENTS 权限）。
 * MotionEvent 为框架 Parcelable，可直接通过 Binder 传输；事件所有权移交服务端（服务端负责 recycle）。
 */
package top.loongway.autoresolution.shizuku;

import android.view.MotionEvent;

interface IInputInjector {
    /** 通用事件注入：服务端会把事件定向到 [displayId] 后调用 injectInputEvent */
    boolean injectMotionEvent(in MotionEvent event, int displayId) = 1;

    /** 便捷点击：在指定显示器 (x, y) 处注入一次 ACTION_DOWN + ACTION_UP */
    boolean injectTap(int displayId, int x, int y) = 2;

    /** 便捷滚轮：ACTION_SCROLL 单事件携带 vTicks/hTicks 格滚动（正数 = 内容向下/向右翻） */
    boolean injectScroll(int displayId, int x, int y, float vTicks, float hTicks) = 3;

    void destroy() = 16777114; // Shizuku reserved
}
