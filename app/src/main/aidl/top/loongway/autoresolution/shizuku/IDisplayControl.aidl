/**
 * 屏幕电源控制接口。通过 SurfaceControl 隐藏 API 控制显示电源的开启/关闭。
 */
package top.loongway.autoresolution.shizuku;

interface IDisplayControl {
    boolean setPowerModeToSurfaceControl(int mode) = 1;
    void destroy() = 16777114;
}
