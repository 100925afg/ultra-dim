package com.ultradim;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

/**
 * 常驻前台服务：维护全屏遮罩窗口（TYPE_APPLICATION_OVERLAY）与浮球控制窗口。
 *
 * <p>压暗方式有两种：
 * <ul>
 *   <li>普通模式：黑色不透明 View + 窗口 {@code params.alpha = opacity/100}</li>
 *   <li>实验模式：透明 View + {@code FLAG_DIM_BEHIND} + {@code dimAmount = opacity/100}</li>
 * </ul>
 * 普通模式下窗口 alpha 会被 Android 12+ 的「不可信触摸」规则用于判断遮挡程度，
 * 超过 0.80 时触摸可能无法透传到下层应用，因此安全模式把上限锁在 80%。
 */
public class DimService extends Service {

    public static final String ACTION_START = "com.ultradim.action.START";
    public static final String ACTION_STOP = "com.ultradim.action.STOP";
    public static final String ACTION_TOGGLE = "com.ultradim.action.TOGGLE";
    public static final String ACTION_INC = "com.ultradim.action.INC";
    public static final String ACTION_DEC = "com.ultradim.action.DEC";
    public static final String ACTION_SET = "com.ultradim.action.SET";
    public static final String ACTION_APPLY = "com.ultradim.action.APPLY";
    public static final String ACTION_KNOB = "com.ultradim.action.KNOB";

    public static final String EXTRA_OPACITY = "extra_opacity";
    public static final String EXTRA_STEP = "extra_step";

    public static final String ACTION_STATE = "com.ultradim.action.STATE";
    public static final String EXTRA_RUNNING = "extra_running";
    public static final String EXTRA_OPACITY_VALUE = "extra_opacity_value";

    private static final int NOTIF_ID = 0x5544;
    private static final int NOTIF_STEP = 5;
    private static final String CHANNEL_ID = "dim_service";
    private static final String CHANNEL_NAME = "极暗遮罩";
    private static final String TAG = "UltraDim";
    /** 浮球直径（dp），必须与 KnobView 内部 sizePx 保持一致。 */
    private static final int KNOB_DP = 72;

    private static volatile boolean sRunning = false;
    private static volatile DimService sInstance;

    private WindowManager wm;
    private State state;

    private View dimView;
    private WindowManager.LayoutParams dimParams;

    private KnobView knobView;
    private WindowManager.LayoutParams knobParams;
    /** 浮球实际所在的 WindowManager（深度模式下借用无障碍 overlay 的 wm）。 */
    private WindowManager knobWm;

    private int opacity = State.DEFAULT_OPACITY;
    private int lastNonZero = State.DEFAULT_OPACITY;
    private boolean foregroundStarted = false;
    /** 本次服务会话内用户长按隐藏了浮球（不写回偏好，重启后恢复）。 */
    private boolean knobHiddenByUser = false;
    /** 深度模式的无障碍图层当前是否真的加上了（addView 成功）。 */
    private boolean deepLayerOk = false;
    /** 深度图层失败提示是否已弹过，避免每次拖动都 toast。 */
    private boolean deepFailToastShown = false;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable notifyRunnable = new Runnable() {
        @Override
        public void run() {
            if (sRunning) {
                updateNotification();
                broadcastState();
            }
        }
    };

    // ------------------------------------------------------------------
    // 静态入口
    // ------------------------------------------------------------------

    public static boolean isRunning() {
        return sRunning;
    }

    public static void start(Context c) {
        Intent i = new Intent(c, DimService.class).setAction(ACTION_START);
        try {
            c.startForegroundService(i);
        } catch (Throwable t) {
            try {
                c.startService(i);
            } catch (Throwable ignored) {
            }
        }
    }

    public static void stop(Context c) {
        Intent i = new Intent(c, DimService.class).setAction(ACTION_STOP);
        // 不要用 sRunning 判断服务是否存在：sRunning 要等 onStartCommand 末尾才置 true，
        // 「刚打开立刻关闭」时会误判。直接发指令，服务若不存在也会被创建后立刻 shutdown。
        try {
            c.startService(i);
        } catch (Throwable t) {
            try {
                c.startForegroundService(i);
            } catch (Throwable ignored) {
            }
        }
    }

    public static void send(Context c, String action) {
        Intent i = new Intent(c, DimService.class).setAction(action);
        try {
            c.startService(i);
        } catch (Throwable ignored) {
        }
    }

    public static void setOpacity(Context c, int opacity) {
        Intent i = new Intent(c, DimService.class)
                .setAction(ACTION_SET)
                .putExtra(EXTRA_OPACITY, opacity);
        try {
            c.startService(i);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 供 DeepDimService 等进程内组件调用：深度模式连接状态变化后立即重算遮罩。
     * 必须在主线程调用（无障碍回调本身就是主线程）。
     */
    public static void requestExternalRefresh() {
        DimService s = sInstance;
        if (s == null) return;
        try {
            s.opacity = s.state.effectiveOpacity();
            s.applyWindow();
            s.syncKnob();
            s.scheduleNotifyAndBroadcast();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 供 DeepDimService 调用：无障碍连接/断开后没有 Activity 的 onResume 可靠路径，
     * 这里主动刷新通知与状态广播，否则界面会一直停在「⚠ 未连接」。
     */
    public static void notifyStateChanged() {
        DimService s = sInstance;
        if (s == null) return;
        try {
            s.scheduleNotifyAndBroadcast();
        } catch (Throwable ignored) {
        }
    }

    /** 深度图层此刻是否真的加在屏幕上（false = 无障碍已连接但图层创建失败、已回退 80%）。 */
    public static boolean isDeepLayerOk() {
        DimService s = sInstance;
        // 服务没在跑时不存在「图层失效」，避免把「未启用」误报成失败
        return s == null || s.deepLayerOk || s.opacity <= 0;
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        state = new State(this);
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        opacity = state.effectiveOpacity();
        lastNonZero = opacity > 0 ? opacity : State.DEFAULT_OPACITY;
        createChannel();
        startForegroundCompat();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();

        // 系统用 START_STICKY 把服务拉起来（intent 为 null）时，如果用户已经主动关闭过，不要自己复活
        if (action == null && !state.isEnabled()) {
            shutdownInternal(false);
            return START_NOT_STICKY;
        }
        if (action == null) action = ACTION_START;

        if (ACTION_TOGGLE.equals(action)) {
            if (sRunning) {
                shutdown();
                return START_NOT_STICKY;
            }
            action = ACTION_START;
        }
        if (ACTION_STOP.equals(action)) {
            shutdown();
            return START_NOT_STICKY;
        }

        if (!Settings.canDrawOverlays(this)) {
            toast("请先授予「悬浮窗 / 显示在其他应用上层」权限");
            broadcastState();
            shutdown();
            return START_NOT_STICKY;
        }

        // 任何非 STOP 动作都意味着需要遮罩处于可见状态
        if (dimView == null) {
            if (!showOverlay()) {
                shutdown();
                return START_NOT_STICKY;
            }
        }

        // 先按持久化状态恢复；0 是合法值，不要抹成默认值
        int restore = state.effectiveOpacity();
        if (restore != opacity) {
            opacity = restore;
            if (restore > 0) lastNonZero = restore;
        }
        applyWindow();

        switch (action) {
            case ACTION_SET:
                if (intent != null) {
                    setOpacityInternal(intent.getIntExtra(EXTRA_OPACITY, opacity));
                }
                break;
            case ACTION_INC:
                setOpacityInternal(opacity + stepOf(intent));
                break;
            case ACTION_DEC:
                setOpacityInternal(opacity - stepOf(intent));
                break;
            case ACTION_KNOB:
                state.setKnobEnabled(!state.isKnobEnabled());
                knobHiddenByUser = false;
                syncKnob();
                break;
            case ACTION_APPLY:
                // 界面把「浮球调节」开关打开时会走这里：必须同时解除长按隐藏，
                // 否则 knobHiddenByUser 一直是 true，开关打开了浮球也永远不出现
                // （只能靠关掉遮罩再开、或重启服务才能复位）。
                if (state.isKnobEnabled()) knobHiddenByUser = false;
                syncKnob();
                break;
            case ACTION_START:
            default:
                syncKnob();
                break;
        }

        sRunning = true;
        state.setEnabled(true);
        applyWindow();
        syncKnob();
        scheduleNotifyAndBroadcast();
        return START_STICKY;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        clampKnobToScreen();
    }

    @Override
    public void onDestroy() {
        mainHandler.removeCallbacks(notifyRunnable);
        removeOverlay();
        removeKnob();
        DeepDimService.setDimLevel(0f);
        sRunning = false;
        sInstance = null;
        // 注意：系统回收/重建也会走 onDestroy，这里【不要】清 state.enabled，
        // 否则开机自恢复与任务被回收后的重建都会失效。用户主动关闭走 shutdown()。
        broadcastState();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** 用户主动关闭：持久化 enabled=false 并停止服务。 */
    private void shutdown() {
        shutdownInternal(true);
    }

    private void shutdownInternal(boolean userInitiated) {
        if (userInitiated) {
            state.setEnabled(false);
        }
        mainHandler.removeCallbacks(notifyRunnable);
        removeKnob();
        removeOverlay();
        DeepDimService.setDimLevel(0f);
        sRunning = false;
        sInstance = null;
        broadcastState();
        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            foregroundStarted = false;
        }
        stopSelf();
    }

    // ------------------------------------------------------------------
    // 遮罩窗口
    // ------------------------------------------------------------------

    private boolean showOverlay() {
        try {
            dimParams = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT);
            dimParams.gravity = Gravity.TOP | Gravity.START;
            dimParams.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            dimParams.setFitInsetsTypes(0);
            dimParams.setTitle("UltraDimOverlay");

            dimView = new View(this);
            dimView.setBackgroundColor(Color.BLACK);
            dimView.setClickable(false);
            dimView.setFocusable(false);
            wm.addView(dimView, dimParams);
            applyWindow();
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "showOverlay failed", t);
            toast("无法添加遮罩: " + t.getClass().getSimpleName());
            dimView = null;
            return false;
        }
    }

    /** 根据当前 opacity / 模式刷新窗口参数。 */
    private void applyWindow() {
        if (dimView == null || dimParams == null) return;

        // 设置变化后可能越界（例如关掉深度模式或打开安全触摸），这里统一夹一次
        int target = state.clampToEffectiveMax(opacity);
        if (target != opacity) {
            opacity = target;
            state.setOpacity(target);
        }

        boolean dimBehind = state.isDimBehind();
        boolean deep = state.isDeepActive();
        float frac = Math.max(0f, Math.min(1f, opacity / 100f));

        try {
            if (dimBehind) {
                // 实验模式：dim 层不参与遮挡计算，理论上可以超过 80% 且触摸透传（待真机验证）
                dimParams.flags |= WindowManager.LayoutParams.FLAG_DIM_BEHIND;
                dimParams.dimAmount = frac;
                dimParams.alpha = 0.01f;
                dimView.setBackgroundColor(Color.TRANSPARENT);
                DeepDimService.setDimLevel(0f);
                deepLayerOk = false;
            } else if (deep) {
                // 深度模式：全部压暗交给无障碍图层（trusted overlay，不参与遮挡计算），
                // 普通遮罩退到 0，避免出现「状态栏没被压暗」的割裂感。
                dimParams.flags &= ~WindowManager.LayoutParams.FLAG_DIM_BEHIND;
                dimParams.dimAmount = 0f;
                dimView.setBackgroundColor(Color.TRANSPARENT);
                boolean ok = DeepDimService.setDimLevel(frac);
                deepLayerOk = ok;
                if (ok || frac <= 0.002f) {
                    dimParams.alpha = 0f;
                } else {
                    // 无障碍图层没能加成功：绝不留在 alpha=0（那等于完全没有压暗，用户会以为坏掉了），
                    // 立刻退回普通遮罩的安全上限，并提示一次。
                    dimParams.alpha = Math.min(frac, State.TOUCH_SAFE_LIMIT / 100f);
                    dimView.setBackgroundColor(Color.BLACK);
                    if (!deepFailToastShown) {
                        deepFailToastShown = true;
                        Log.e(TAG, "deep layer failed, fallback to normal overlay");
                        toast("深度图层创建失败（请给「极暗」开启悬浮窗权限），已退回普通模式（最高 80%）");
                    }
                }
            } else {
                dimParams.flags &= ~WindowManager.LayoutParams.FLAG_DIM_BEHIND;
                dimParams.dimAmount = 0f;
                dimParams.alpha = frac;
                dimView.setBackgroundColor(Color.BLACK);
                DeepDimService.setDimLevel(0f);
                deepLayerOk = false;
            }
            wm.updateViewLayout(dimView, dimParams);
            if (knobView != null) knobView.setOpacity(opacity);
        } catch (Throwable t) {
            Log.e(TAG, "applyWindow failed", t);
        }
    }

    private void removeOverlay() {
        if (dimView == null) return;
        try {
            wm.removeViewImmediate(dimView);
        } catch (Throwable ignored) {
        }
        dimView = null;
        dimParams = null;
    }

    private void setOpacityInternal(int value) {
        int clamped = state.clampToEffectiveMax(value);
        opacity = clamped;
        if (clamped > 0) lastNonZero = clamped;
        state.setOpacity(clamped);
        applyWindow();
        if (knobView != null) knobView.setOpacity(opacity);
        syncKnob();
        scheduleNotifyAndBroadcast();
    }

    // ------------------------------------------------------------------
    // 浮球
    // ------------------------------------------------------------------

    private void syncKnob() {
        // 深度模式（图层确实生效）/实验模式下触摸安全，只有普通模式超过 80% 才强制显示逃生浮球
        boolean deepWorks = state.isDeepActive() && deepLayerOk;
        boolean unsafe = opacity > State.TOUCH_SAFE_LIMIT
                && !deepWorks
                && !state.isDimBehind();
        // 深度模式下无障碍图层盖在浮球之上（该层任何不透明度都比浮球高），
        // 所以只要深度模式开着且无障碍 WM 可用，就固定把浮球放到无障碍图层。
        // 这里不看 deepLayerOk：那一项会随图层偶发失败/0 档抖动翻转，跟着它走会让浮球来回迁移重建。
        boolean useA11yWm = false;
        WindowManager wantWm = wm;
        if (state.isDeepActive()) {
            WindowManager aw = DeepDimService.overlayWindowManager();
            if (aw != null) {
                wantWm = aw;
                useA11yWm = true;
            }
        }

        boolean want = unsafe || (state.isKnobEnabled() && !knobHiddenByUser);
        if (want) {
            if (knobView != null && knobWm != wantWm) {
                // 窗口上下文变了（开关深度模式）：不能在触摸回调栈里同步 removeViewImmediate
                // （正在被触摸的窗口被摘掉会导致拖动中断、偶发 Input dispatching timed out），
                // 先摘旧窗口、post 到下一个消息循环再建新窗口。
                final KnobView old = knobView;
                final WindowManager oldWm = knobWm;
                final WindowManager targetWm = wantWm;
                final boolean targetA11y = useA11yWm;
                knobView = null;
                knobParams = null;
                knobWm = null;
                old.post(new Runnable() {
                    @Override
                    public void run() {
                        detachKnobView(old, oldWm);
                        if (knobView == null) showKnob(targetWm, targetA11y);
                    }
                });
                return;
            }
            if (knobView == null) showKnob(wantWm, useA11yWm);
            if (knobView != null) knobView.setOpacity(opacity);
        } else if (knobView != null) {
            // 不要在触摸回调里同步 removeViewImmediate，post 到下一个消息循环
            final KnobView v = knobView;
            final WindowManager vm = knobWm;
            knobView = null;
            knobParams = null;
            knobWm = null;
            v.post(new Runnable() {
                @Override
                public void run() {
                    detachKnobView(v, vm);
                }
            });
        }
    }

    private void detachKnobView(View v, WindowManager mgr) {
        try {
            (mgr != null ? mgr : wm).removeViewImmediate(v);
        } catch (Throwable t) {
            Log.w(TAG, "removeKnob failed", t);
        }
    }

    private void showKnob(WindowManager useWm, boolean a11y) {
        if (knobView != null) return;
        try {
            knobView = new KnobView(this, new KnobView.Callback() {
                @Override
                public void onOpacityChanged(int value) {
                    setOpacityInternal(value);
                }

                @Override
                public void onToggle() {
                    if (opacity > 0) {
                        lastNonZero = opacity;
                        setOpacityInternal(0);
                    } else {
                        setOpacityInternal(lastNonZero <= 0 ? State.DEFAULT_OPACITY : lastNonZero);
                    }
                }

                @Override
                public void onDismiss() {
                    // 普通模式超过 80% 时，浮球是唯一的操作入口（触摸会被系统拦掉），
                    // 长按隐藏会把自己锁死在打不开的状态，这里直接拒绝。
                    boolean deepWorks = state.isDeepActive() && deepLayerOk;
                    if (opacity > State.TOUCH_SAFE_LIMIT && !deepWorks && !state.isDimBehind()) {
                        toast("当前亮度超过 80%，浮球是唯一的操作入口，不能隐藏");
                        return;
                    }
                    knobHiddenByUser = true;
                    state.setKnobEnabled(false);
                    syncKnob();
                    scheduleNotifyAndBroadcast();
                }
            });
            knobView.setOpacity(opacity);

            // 借用无障碍图层的 WindowManager 时，窗口类型也必须是无障碍 overlay，
            // 否则 token 与 type 不匹配会被 WMS 以 ADD_BAD_APP_TOKEN 拒绝。
            // 不要靠 WindowManager 对象的同一性去猜类型，显式传入。
            int knobType = a11y
                    ? WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                    : WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
            knobParams = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    knobType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT);
            knobParams.gravity = Gravity.TOP | Gravity.START;

            Point size = screenSize();
            int knobSize = dp(KNOB_DP);
            int x = state.getKnobX();
            int y = state.getKnobY();
            if (x < 0 || x > size.x - knobSize) x = size.x - knobSize - dp(4);
            if (y < 0 || y > size.y - knobSize) y = size.y / 3;
            knobParams.x = clamp(x, 0, Math.max(0, size.x - knobSize));
            knobParams.y = clamp(y, 0, Math.max(0, size.y - knobSize));
            state.setKnobPosition(knobParams.x, knobParams.y);

            knobWm = useWm;
            useWm.addView(knobView, knobParams);
        } catch (Throwable t) {
            Log.e(TAG, "showKnob failed", t);
            // addView 抛在 attach 之后时窗口可能已经挂上去了，先尽力摘掉，避免泄漏出摘不掉的浮球
            try {
                if (knobView != null) (useWm != null ? useWm : wm).removeViewImmediate(knobView);
            } catch (Throwable ignored) {
            }
            knobView = null;
            knobParams = null;
            knobWm = null;
            // >80% 时浮球是唯一软着陆方式，失败必须告诉用户，并考虑自保
            toast("浮球添加失败，请用通知栏按钮调节亮度");
        }
    }

    private void removeKnob() {
        if (knobView == null) return;
        detachKnobView(knobView, knobWm);
        knobView = null;
        knobParams = null;
        knobWm = null;
    }

    private void clampKnobToScreen() {
        if (knobView == null || knobParams == null) return;
        try {
            Point size = screenSize();
            int knobSize = dp(KNOB_DP);
            knobParams.x = clamp(knobParams.x, 0, Math.max(0, size.x - knobSize));
            knobParams.y = clamp(knobParams.y, 0, Math.max(0, size.y - knobSize));
            state.setKnobPosition(knobParams.x, knobParams.y);
            (knobWm != null ? knobWm : wm).updateViewLayout(knobView, knobParams);
        } catch (Throwable t) {
            Log.w(TAG, "clampKnobToScreen failed", t);
        }
    }

    // ------------------------------------------------------------------
    // 通知
    // ------------------------------------------------------------------

    private void createChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("保持屏幕遮罩常驻运行");
        ch.setShowBadge(false);
        ch.enableVibration(false);
        ch.setSound(null, null);
        nm.createNotificationChannel(ch);
    }

    private void startForegroundCompat() {
        if (foregroundStarted) return;
        Notification n = buildNotificationSafe();
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIF_ID, n);
            }
            foregroundStarted = true;
        } catch (Throwable t) {
            Log.e(TAG, "startForeground(specialUse) failed, retry without type", t);
            try {
                // 兜底：不指定 type。只要有一个前台通知，就不会触发 5s RemoteServiceException。
                startForeground(NOTIF_ID, n);
                foregroundStarted = true;
            } catch (Throwable t2) {
                Log.e(TAG, "startForeground failed, stop self to avoid 5s crash", t2);
                foregroundStarted = false;
                sRunning = false;
                try {
                    state.setEnabled(false);
                } catch (Throwable ignored) {
                }
                broadcastState();
                toast("前台服务启动失败：" + t2.getClass().getSimpleName());
                stopSelf();
            }
        }
    }

    private Notification buildNotificationSafe() {
        try {
            return buildNotification();
        } catch (Throwable t) {
            Log.e(TAG, "buildNotification failed", t);
            try {
                return new Notification.Builder(this, CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_tile)
                        .setContentTitle("极暗")
                        .setContentText("遮罩运行中")
                        .setOngoing(true)
                        .build();
            } catch (Throwable t2) {
                return new Notification();
            }
        }
    }

    private void updateNotification() {
        if (!foregroundStarted) {
            startForegroundCompat();
            if (!foregroundStarted) return;
        }
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.notify(NOTIF_ID, buildNotificationSafe());
        } catch (Throwable t) {
            Log.w(TAG, "notify failed", t);
        }
    }

    /** 通知与状态广播做 120ms 合并，避免拖动时高频 IPC / 通知栏抖动。 */
    private void scheduleNotifyAndBroadcast() {
        mainHandler.removeCallbacks(notifyRunnable);
        mainHandler.postDelayed(notifyRunnable, 120);
    }

    private Notification buildNotification() {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        PendingIntent dec = PendingIntent.getService(this, 1,
                new Intent(this, DimService.class).setAction(ACTION_DEC)
                        .putExtra(EXTRA_STEP, NOTIF_STEP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent inc = PendingIntent.getService(this, 2,
                new Intent(this, DimService.class).setAction(ACTION_INC)
                        .putExtra(EXTRA_STEP, NOTIF_STEP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent off = PendingIntent.getService(this, 3,
                new Intent(this, DimService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_tile)
                .setContentTitle("极暗 · " + opacity + "%")
                .setContentText(dimView != null ? "遮罩运行中" : "遮罩未启用")
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_PUBLIC);
        Icon icon = Icon.createWithResource(this, R.drawable.ic_tile);
        b.addAction(new Notification.Action.Builder(icon, "暗一点", dec).build());
        b.addAction(new Notification.Action.Builder(icon, "亮一点", inc).build());
        b.addAction(new Notification.Action.Builder(icon, "关闭", off).build());
        return b.build();
    }

    private void broadcastState() {
        try {
            Intent i = new Intent(ACTION_STATE).setPackage(getPackageName());
            i.putExtra(EXTRA_RUNNING, sRunning);
            i.putExtra(EXTRA_OPACITY_VALUE, opacity);
            sendBroadcast(i);
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private Point screenSize() {
        try {
            WindowManager mgr = getSystemService(WindowManager.class);
            if (mgr != null) {
                Rect b = mgr.getMaximumWindowMetrics().getBounds();
                if (b.width() > 0 && b.height() > 0) return new Point(b.width(), b.height());
            }
        } catch (Throwable t) {
            Log.w(TAG, "getMaximumWindowMetrics failed", t);
        }
        Point p = new Point();
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        p.x = dm.widthPixels;
        p.y = dm.heightPixels;
        return p;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static int clamp(int v, int min, int max) {
        return v < min ? min : (v > max ? max : v);
    }

    private static int stepOf(Intent intent) {
        if (intent == null) return 1;
        return clamp(intent.getIntExtra(EXTRA_STEP, 1), 1, 20);
    }

    private void toast(String text) {
        try {
            Toast.makeText(getApplicationContext(), text, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }
}
