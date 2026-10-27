package com.ultradim;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

/**
 * 可选「深度模式」：用无障碍服务添加 {@code TYPE_ACCESSIBILITY_OVERLAY} 全屏黑色图层。
 *
 * <p>为什么需要它：Android 12+ 规定，非 trusted 的全屏悬浮窗不透明度 &gt; 0.8 时会吞掉触摸事件，
 * 所以普通 {@code TYPE_APPLICATION_OVERLAY} 只能安全压暗到 80%。而
 * {@code TYPE_ACCESSIBILITY_OVERLAY} 被系统视为 trusted overlay
 * （{@code InputDispatcher::canBeObscuredBy} 对 trusted overlay 直接返回 false），
 * 完全不参与遮挡计算 —— 因此它可以用任意 alpha 压暗，而触摸依然透传到下层应用。
 *
 * <p>组合方式：深度模式**只**用这一层承载全部压暗（alpha = 目标值），普通遮罩同时退到 0。
 * 这样状态栏/导航栏/输入法也被同一层均匀压暗，不会出现「状态栏比应用亮」的割裂；
 * 而 trusted overlay 不参与遮挡计算，触摸依然可用。三层叠加的算术在这里没有必要。
 *
 * <p>使用前提：用户在系统设置里手动开启本应用的「无障碍服务」。未开启时本类不会创建任何窗口，
 * DimService 自动退回普通模式（上限 80%）。
 */
public class DeepDimService extends AccessibilityService {

    private static final String TAG = "UltraDim";

    private static volatile DeepDimService sInstance;

    private Context windowContext;
    private WindowManager windowManager;
    private View dimView;
    private WindowManager.LayoutParams dimParams;
    private float currentAlpha = 0f;

    public static boolean isConnected() {
        return sInstance != null;
    }

    /** 深度图层当前是否真的加在屏幕上（用于 UI 如实显示「已生效」）。 */
    public static boolean hasLayer() {
        DeepDimService s = sInstance;
        return s != null && s.dimView != null;
    }

    /** 无障碍 overlay 图层的 WindowManager（层级高于状态栏）；未连接时为 null。 */
    public static WindowManager overlayWindowManager() {
        DeepDimService s = sInstance;
        return s == null ? null : s.windowManager;
    }

    /**
     * 供 DimService 调用：设置深度压暗强度，0..1。
     *
     * <p>alpha == 0 时**不销毁窗口**，只把窗口 alpha 归零 —— 避免「0% ↔ 非 0%」来回穿越时
     * 反复 {@code createWindowContext} + addView（createWindowContext 是昂贵操作，且会让
     * 借用的 WindowManager 瞬间为 null，导致浮球窗口被反复迁移）。
     *
     * @return 图形层是否处于可用状态。false 表示没有连接、window context 创建失败或 addView 失败，
     *         调用方应退回普通遮罩，否则会出现「屏幕上没有任何压暗、UI 却显示深度模式已生效」。
     */
    public static boolean setDimLevel(float alpha) {
        DeepDimService s = sInstance;
        if (s == null) return false;
        return s.applyAlpha(alpha);
    }

    // ------------------------------------------------------------------

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        sInstance = this;
        // 提前建好 window context：DimService 需要借用它的 WindowManager 把浮球放到深度层之上
        ensureWindowManager();
        Log.i(TAG, "DeepDimService connected, wm=" + (windowManager != null));
        // 若普通遮罩正在运行，让它按深度模式重新计算（内部会刷新通知/广播状态）
        DimService.requestExternalRefresh();
        DimService.notifyStateChanged();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 不做任何事件处理；本服务只借用无障碍图层的「trusted overlay」身份。
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        release();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        release();
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // 旋转/尺寸变化后让窗口重新贴合屏幕
        if (dimView != null && dimParams != null) {
            try {
                dimParams = buildParams(currentAlpha);
                windowManager.updateViewLayout(dimView, dimParams);
            } catch (Throwable t) {
                // 失败后不要留下停在新旧尺寸之间的残窗，交给下一次 setDimLevel 重建
                Log.w(TAG, "relayout on config change failed, drop layer", t);
                removeView();
            }
        }
    }

    private void release() {
        sInstance = null;
        removeView();
        windowContext = null;
        windowManager = null;
        Log.i(TAG, "DeepDimService released");
        DimService.requestExternalRefresh();
        DimService.notifyStateChanged();
    }

    // ------------------------------------------------------------------

    private boolean applyAlpha(float alpha) {
        float a = Math.max(0f, Math.min(1f, alpha));
        float previous = currentAlpha;
        currentAlpha = a;
        if (a <= 0.002f) {
            // 0 档：保留窗口，只把不透明度归零（见方法注释，避免 windowContext churn）
            if (dimView != null && windowManager != null) {
                try {
                    dimParams.alpha = 0f;
                    windowManager.updateViewLayout(dimView, dimParams);
                } catch (Throwable t) {
                    Log.w(TAG, "clear alpha failed, drop layer", t);
                    removeView();
                    return false;
                }
            }
            if (previous > 0.002f) {
                Log.i(TAG, "deep layer dimmed to 0 (window kept)");
            }
            return true;
        }
        try {
            if (!ensureWindowManager()) return false;
            if (dimView == null && !addView()) return false;
            dimParams.alpha = a;
            windowManager.updateViewLayout(dimView, dimParams);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "applyAlpha failed: " + t);
            removeView();
            return false;
        }
    }

    private boolean ensureWindowManager() {
        if (windowManager != null) return true;
        try {
            // 必须使用 createWindowContext(TYPE_ACCESSIBILITY_OVERLAY) 生成的 context，
            // 由系统注入 accessibility overlay 的 window token，否则 WMS 返回 ADD_BAD_APP_TOKEN。
            windowContext = createWindowContext(
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, (Bundle) null);
            windowManager = (WindowManager) windowContext.getSystemService(Context.WINDOW_SERVICE);
            return windowManager != null;
        } catch (Throwable t) {
            Log.w(TAG, "createWindowContext failed: " + t);
            windowManager = null;
            windowContext = null;
            return false;
        }
    }

    private boolean addView() {
        try {
            if (!ensureWindowManager()) return false;

            dimParams = buildParams(currentAlpha);
            View v = new View(this);
            v.setBackgroundColor(Color.BLACK);
            v.setClickable(false);
            v.setFocusable(false);
            v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            windowManager.addView(v, dimParams);
            dimView = v;
            Log.i(TAG, "a11y overlay added, alpha=" + dimParams.alpha);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "addView failed: " + t);
            dimView = null;
            dimParams = null;
            return false;
        }
    }

    private WindowManager.LayoutParams buildParams(float alpha) {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START;
        p.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        p.setFitInsetsTypes(0);
        p.alpha = Math.max(0f, Math.min(1f, alpha));
        p.setTitle("UltraDimDeepLayer");
        return p;
    }

    /** 摘掉图层，但保留 windowContext / windowManager（供浮球继续借用）。 */
    private void removeView() {
        if (dimView == null) return;
        try {
            windowManager.removeViewImmediate(dimView);
        } catch (Throwable t) {
            Log.w(TAG, "removeView failed: " + t);
        }
        dimView = null;
        dimParams = null;
    }
}
