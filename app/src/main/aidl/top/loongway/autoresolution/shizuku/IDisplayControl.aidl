/**
 * 屏幕电源/亮度控制接口。通过 SurfaceControl 隐藏 API 控制：
 * 电源模式（硬关屏，触摸中断）与显示亮度（关背光但保触摸，息屏触摸方案）。
 * settings 接口用于让系统按自身换算重新套用亮度（避免手工换算 float 比例）。
 */
package top.loongway.autoresolution.shizuku;

interface IDisplayControl {
    boolean setPowerModeToSurfaceControl(int mode) = 1;
    boolean setBrightnessToSurfaceControl(float brightness) = 2;
    boolean setBrightnessToSetting(int brightness) = 3;
    boolean setBrightnessModeToSetting(int mode) = 4;
    void destroy() = 16777114;
}
