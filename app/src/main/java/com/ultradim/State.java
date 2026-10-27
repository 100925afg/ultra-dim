package com.ultradim;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 全局状态（SharedPreferences 持久化）。
 *
 * <p>约定：
 * <ul>
 *   <li>opacity 是「遮罩不透明度百分比」，0..95，物理含义是屏幕被压暗的程度；</li>
 *   <li>effectiveMaxOpacity() 是当前设置下允许的最大值，受 maxOpacity 与 safeTouch 两个开关约束；</li>
 *   <li>Android 12+ 的「不可信触摸」规则：全屏悬浮窗不透明度 &gt; 0.80 时，触摸可能无法透传到下层应用，
 *       因此 safeTouch=true 时上限锁 80%。</li>
 * </ul>
 */
public final class State {

    public static final String PREFS = "ultradim_state";

    /** 总开关：服务是否应处于运行状态（开机自恢复也读它）。 */
    public static final String K_ENABLED = "enabled";
    /** 当前遮罩不透明度百分比，0..95。 */
    public static final String K_OPACITY = "opacity";
    /** 用户可调上限，50..95，默认 85。 */
    public static final String K_MAX_OPACITY = "max_opacity";
    /** 安全触摸模式：为 true 时实际上限被压到 80%。 */
    public static final String K_SAFE_TOUCH = "safe_touch";
    /** 是否显示浮球。 */
    public static final String K_KNOB = "knob";
    public static final String K_KNOB_X = "knob_x";
    public static final String K_KNOB_Y = "knob_y";
    /** 实验模式：用 FLAG_DIM_BEHIND + dimAmount 实现压暗，而不是窗口 alpha。 */
    public static final String K_DIM_BEHIND = "dim_behind";
    /** 首次启动引导是否看过。 */
    public static final String K_ONBOARDED = "onboarded";
    /** 深度模式：用无障碍图层（TYPE_ACCESSIBILITY_OVERLAY）叠加额外压暗，可突破 80% 且触摸仍然可用。 */
    public static final String K_DEEP_MODE = "deep_mode";
    /** 无障碍服务是否已连接（由 DeepDimService 写入，供 UI 展示状态）。 */
    public static final String K_DEEP_CONNECTED = "deep_connected";

    /** Android 12+ 触摸透传的硬上限（含）。 */
    public static final int TOUCH_SAFE_LIMIT = 80;

    public static final int DEFAULT_OPACITY = 40;
    public static final int DEFAULT_MAX_OPACITY = 85;

    private final SharedPreferences sp;

    public State(Context context) {
        this.sp = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public boolean isEnabled() {
        return sp.getBoolean(K_ENABLED, false);
    }

    public void setEnabled(boolean enabled) {
        sp.edit().putBoolean(K_ENABLED, enabled).apply();
    }

    public int getOpacity() {
        return clamp(sp.getInt(K_OPACITY, DEFAULT_OPACITY), 0, 95);
    }

    public void setOpacity(int opacity) {
        sp.edit().putInt(K_OPACITY, clamp(opacity, 0, 95)).apply();
    }

    public int getMaxOpacity() {
        return clamp(sp.getInt(K_MAX_OPACITY, DEFAULT_MAX_OPACITY), 50, 95);
    }

    public void setMaxOpacity(int max) {
        sp.edit().putInt(K_MAX_OPACITY, clamp(max, 50, 95)).apply();
    }

    public boolean isSafeTouch() {
        return sp.getBoolean(K_SAFE_TOUCH, true);
    }

    public void setSafeTouch(boolean safe) {
        sp.edit().putBoolean(K_SAFE_TOUCH, safe).apply();
    }

    public boolean isKnobEnabled() {
        return sp.getBoolean(K_KNOB, true);
    }

    public void setKnobEnabled(boolean enabled) {
        sp.edit().putBoolean(K_KNOB, enabled).apply();
    }

    public boolean isDimBehind() {
        return sp.getBoolean(K_DIM_BEHIND, false);
    }

    public void setDimBehind(boolean on) {
        sp.edit().putBoolean(K_DIM_BEHIND, on).apply();
    }

    public boolean isDeepMode() {
        return sp.getBoolean(K_DEEP_MODE, false);
    }

    public void setDeepMode(boolean on) {
        sp.edit().putBoolean(K_DEEP_MODE, on).apply();
    }

    /**
     * 无障碍服务当前是否真的连着。
     *
     * <p>不能只信 SharedPreferences 里的 K_DEEP_CONNECTED：那样服务被杀/被系统关闭后会一直残留 true，
     * 结果 UI 显示「已连接」而实际没有图层，用户会以为 85% 是安全的。这里以进程内活对象为准。
     */
    public boolean isDeepConnected() {
        return DeepDimService.isConnected();
    }

    public void setDeepConnected(boolean on) {
        sp.edit().putBoolean(K_DEEP_CONNECTED, on).apply();
    }

    /** 深度模式是否真正可用（开关打开且无障碍服务已连接）。 */
    public boolean isDeepActive() {
        return isDeepMode() && isDeepConnected();
    }

    public boolean isOnboarded() {
        return sp.getBoolean(K_ONBOARDED, false);
    }

    public void setOnboarded(boolean v) {
        sp.edit().putBoolean(K_ONBOARDED, v).apply();
    }

    public int getKnobX() {
        return sp.getInt(K_KNOB_X, -1);
    }

    public int getKnobY() {
        return sp.getInt(K_KNOB_Y, -1);
    }

    public void setKnobPosition(int x, int y) {
        sp.edit().putInt(K_KNOB_X, x).putInt(K_KNOB_Y, y).apply();
    }

    /** 当前设置下允许的最大不透明度：受 maxOpacity、safeTouch 与深度模式共同约束。 */
    public int effectiveMaxOpacity() {
        int max = getMaxOpacity();
        // 深度模式下额外压暗由无障碍图层（trusted overlay，不参与遮挡计算）承担，
        // 普通悬浮窗始终 ≤80%，因此触摸安全，上限可以放开。
        if (isDeepActive()) {
            return max;
        }
        if (isSafeTouch()) {
            max = Math.min(max, TOUCH_SAFE_LIMIT);
        }
        return max;
    }

    /** 把任意值夹到当前允许区间。 */
    public int clampToEffectiveMax(int value) {
        return clamp(value, 0, effectiveMaxOpacity());
    }

    /** 当前实际生效的不透明度（存储值再按上限夹一次，防止改设置后越界）。 */
    public int effectiveOpacity() {
        return clampToEffectiveMax(getOpacity());
    }

    public static int clamp(int value, int min, int max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }
}
