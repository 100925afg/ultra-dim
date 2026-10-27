package com.ultradim;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 极暗主界面：纯代码构建 UI（无 AndroidX / 无 XML 布局 / 无第三方库）。
 *
 * <p>本类只负责展示与交互，所有状态写入 {@link State}，遮罩控制通过 {@link DimService} 的静态方法。
 * 深色主题色板见下方常量，与 res/values/colors.xml 保持一致。
 */
public class MainActivity extends Activity {

    // ---------------- 主题色板 ----------------
    private static final int C_BG = 0xFF0B0C0E;
    private static final int C_CARD = 0xFF17191E;
    private static final int C_TEXT = 0xFFE8EAF0;
    private static final int C_SUB = 0xFF9AA0AC;
    private static final int C_ACCENT = 0xFF5B8DEF;
    private static final int C_WARN = 0xFFE5A23B;
    private static final int C_BORDER = 0xFF24262D;
    private static final int C_OK = 0xFF6FCF97;
    private static final int C_BTN_LINE = 0xFF30333B;

    private static final int REQ_NOTIFICATIONS = 0x1001;
    private static final int[] PRESETS = {20, 40, 60, 80};
    private static final int[] MAX_CHOICES = {65, 80, 85, 95};

    // ---------------- 运行时状态 ----------------
    private State state;

    private TextView tvStatus;
    private TextView tvPercent;
    private TextView tvMaxHint;

    private Switch swEnable;
    private Switch swKnob;
    private Switch swSafe;
    private Switch swDimBehind;
    /** 深度模式开关（无障碍图层）。 */
    private Switch swDeep;
    /** 深度模式状态副标题（四种状态动态切换）。 */
    private TextView tvDeepStatus;
    /** 深度模式开关的监听器；代码改开关状态时临时摘下，避免回调重新走业务逻辑。 */
    private CompoundButton.OnCheckedChangeListener deepListener;
    /** 用户已打开深度模式但无障碍尚未连接：延迟提交，等 onResume 兜底确认。 */
    private boolean pendingDeep;

    /** 根容器（ScrollView），用于 postDelayed 等。 */
    private View rootView;

    private SeekBar seek;

    private final TextView[] presetViews = new TextView[PRESETS.length];
    private final TextView[] maxViews = new TextView[MAX_CHOICES.length];

    private TextView rowOverlay;
    private TextView rowNotification;
    private TextView rowBattery;
    private TextView rowAutostart;

    /** 代码改控件状态时的抑制计数（嵌套调用安全），避免监听器递归触发。 */
    private int suppressDepth;
    /** 用户正在拖动 SeekBar：此时忽略服务端回传的滞后进度，避免抖动。 */
    private boolean dragging;
    /** 上次「先启用遮罩」提示的时间戳，用于 2 秒节流。 */
    private long lastHintAt;

    private boolean receiverRegistered;
    private BroadcastReceiver stateReceiver;

    private final Handler handler = new Handler(Looper.getMainLooper());

    /** onResume 后 700ms 复查深度模式连接（无障碍 bind 常晚于 onResume 完成）。 */
    private final Runnable deepRecheck = new Runnable() {
        @Override
        public void run() {
            if (isFinishing() || isDestroyed()) return;
            if (pendingDeep && state.isDeepConnected()) {
                pendingDeep = false;
                state.setDeepMode(true);
                if (DimService.isRunning()) {
                    DimService.send(MainActivity.this, DimService.ACTION_APPLY);
                }
            } else if (pendingDeep) {
                // 用户去了无障碍设置但没开（或还没开）：不落盘，开关视觉拨回关闭
                pendingDeep = false;
                toast("未开启无障碍服务，深度模式未启用");
            }
            refreshDeepModeUi();
            syncOpacityUi(state.effectiveOpacity());
        }
    };

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        state = new State(this);

        // Android 15+ 强制 edge-to-edge，setStatusBarColor 已被系统忽略；
        // 页面背景本身是纯黑，状态栏/导航栏透明即可，实际留白由 WindowInsets 处理。
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);

        rootView = buildUi();
        setContentView(rootView);
        refreshAll();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!receiverRegistered) {
            try {
                IntentFilter filter = new IntentFilter(DimService.ACTION_STATE);
                registerReceiver(stateReceiver(), filter, Context.RECEIVER_NOT_EXPORTED);
                receiverRegistered = true;
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从系统权限页 / 无障碍设置页返回后重新读取状态与权限
        refreshAll();
        // 用户可能刚在无障碍设置里开启服务：让服务重新计算深度压暗层
        if (state.isDeepActive() && DimService.isRunning()) {
            DimService.send(this, DimService.ACTION_APPLY);
        }
        // 无障碍服务 bind 常晚于 onResume：延迟 700ms 兜底复查（pendingDeep 时决定是否落盘）
        handler.removeCallbacks(deepRecheck);
        handler.postDelayed(deepRecheck, 700);
    }

    @Override
    protected void onStop() {
        handler.removeCallbacks(deepRecheck);
        if (receiverRegistered) {
            try {
                unregisterReceiver(stateReceiver());
            } catch (Throwable ignored) {
            }
            receiverRegistered = false;
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIFICATIONS) {
            refreshPermissions();
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            toast(granted ? "通知权限已授予" : "未授予通知权限，常驻通知不会显示");
        }
    }

    // ------------------------------------------------------------------
    // 状态广播
    // ------------------------------------------------------------------

    private BroadcastReceiver stateReceiver() {
        if (stateReceiver == null) {
            stateReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent == null) return;
                    boolean running = intent.getBooleanExtra(
                            DimService.EXTRA_RUNNING, DimService.isRunning());
                    // 拖动中不碰进度条：服务端回传的值是滞后的，回写会把滑块往回拽
                    if (!dragging) {
                        int value = intent.getIntExtra(
                                DimService.EXTRA_OPACITY_VALUE, state.effectiveOpacity());
                        state.setOpacity(state.clampToEffectiveMax(value));
                    }
                    applyRunningState(running);
                    // 长按隐藏浮球时服务端会把「浮球调节」关掉并广播，这里必须同步开关视觉，
                    // 否则界面还显示「开」、用户以为开关失灵（实测 bug）。
                    refreshSwitches();
                    // 深度图层可能刚建好（或刚失败回退），状态文案要跟着变
                    refreshDeepModeUi();
                    if (!dragging) {
                        syncOpacityUi(state.effectiveOpacity());
                    }
                }
            };
        }
        return stateReceiver;
    }

    // ------------------------------------------------------------------
    // UI 构建
    // ------------------------------------------------------------------

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(C_BG);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        col.setPadding(pad, dp(24), pad, dp(28));
        // targetSdk 35 + Android 16：强制 edge-to-edge，内容会顶到状态栏下面，
        // 这里按系统栏 / 刘海的实际高度补上下留白（左右原有 padding 保留）。
        col.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                // 老系统 / 非 edge-to-edge 窗口拿到的 top 可能为 0，此时保留原来的 24dp 留白
                v.setPadding(v.getPaddingLeft(),
                        Math.max(bars.top + dp(12), dp(24)),
                        v.getPaddingRight(),
                        Math.max(bars.bottom + dp(16), dp(28)));
                return insets;
            }
        });
        scroll.addView(col, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        buildHeader(col);
        buildMainCard(col);
        buildOptionsCard(col);
        buildPermissionCard(col);
        buildHelpCard(col);
        buildFooter(col);
        return scroll;
    }

    /** 顶部：标题 + 副标题 + 运行状态标签。 */
    private void buildHeader(LinearLayout col) {
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = labelView("极暗", 30, C_TEXT, true);
        titleRow.addView(title, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        tvStatus = labelView("已关闭", 12, C_SUB, true);
        tvStatus.setGravity(Gravity.CENTER);
        tvStatus.setPadding(dp(10), dp(4), dp(10), dp(4));
        titleRow.addView(tvStatus);

        col.addView(titleRow, lpMatch());

        TextView subtitle = labelView("屏幕遮罩 · 更低亮度无极调节", 13, C_SUB, false);
        LinearLayout.LayoutParams slp = lpMatch();
        slp.topMargin = dp(6);
        col.addView(subtitle, slp);
    }

    /** 主控卡片：开关、大号百分比、SeekBar、微调、预设。 */
    private void buildMainCard(LinearLayout col) {
        LinearLayout card = addCard(col, "遮罩控制");

        // 启用遮罩
        swEnable = newSwitch();
        swEnable.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton button, boolean checked) {
                if (isSuppressed()) return;
                if (checked) {
                    enableDim();
                } else {
                    state.setEnabled(false);
                    DimService.stop(MainActivity.this);
                    // 乐观更新：先按用户意图显示「已关闭」，最终以 ACTION_STATE 广播校准
                    applyRunningState(false);
                }
            }
        });
        addSwitchRow(card, "启用遮罩", "黑色全屏悬浮窗覆盖整个屏幕，用来压暗显示", swEnable);

        // 大号百分比
        tvPercent = labelView("0%", 54, C_TEXT, true);
        tvPercent.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams plp = lpMatch();
        plp.topMargin = dp(16);
        card.addView(tvPercent, plp);

        TextView desc = labelView("遮罩不透明度（越高越暗）", 12, C_SUB, false);
        desc.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams dlp = lpMatch();
        dlp.topMargin = dp(2);
        card.addView(desc, dlp);

        // SeekBar
        seek = new SeekBar(this);
        seek.setMax(State.DEFAULT_MAX_OPACITY);
        seek.setProgress(state.effectiveOpacity());
        seek.setProgressTintList(ColorStateList.valueOf(C_ACCENT));
        seek.setThumbTintList(ColorStateList.valueOf(C_ACCENT));
        seek.setProgressBackgroundTintList(ColorStateList.valueOf(C_BORDER));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser || isSuppressed()) return;
                changeOpacity(progress, true);
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                dragging = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                dragging = false;
            }
        });
        LinearLayout.LayoutParams seekLp = lpMatch();
        seekLp.topMargin = dp(10);
        card.addView(seek, seekLp);

        // 微调按钮
        LinearLayout adjustRow = row();
        TextView btnDec = makeButton("-1%", false);
        TextView btnInc = makeButton("+1%", false);
        btnDec.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                changeOpacity(state.effectiveOpacity() - 1, true);
            }
        });
        btnInc.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                changeOpacity(state.effectiveOpacity() + 1, true);
            }
        });
        LinearLayout.LayoutParams decLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        decLp.rightMargin = dp(5);
        LinearLayout.LayoutParams incLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        incLp.leftMargin = dp(5);
        adjustRow.addView(btnDec, decLp);
        adjustRow.addView(btnInc, incLp);
        LinearLayout.LayoutParams arLp = lpMatch();
        arLp.topMargin = dp(12);
        card.addView(adjustRow, arLp);

        // 预设
        LinearLayout presetRow = row();
        for (int i = 0; i < PRESETS.length; i++) {
            final int value = PRESETS[i];
            TextView btn = makeButton(value + "%", false);
            btn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    changeOpacity(value, true);
                }
            });
            presetViews[i] = btn;
            LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) bLp.leftMargin = dp(8);
            presetRow.addView(btn, bLp);
        }
        LinearLayout.LayoutParams prLp = lpMatch();
        prLp.topMargin = dp(10);
        card.addView(presetRow, prLp);

        // 上限提示
        tvMaxHint = labelView("", 11, C_SUB, false);
        LinearLayout.LayoutParams mhLp = lpMatch();
        mhLp.topMargin = dp(10);
        card.addView(tvMaxHint, mhLp);
    }

    /** 选项卡片：浮球、安全触摸、实验暗度层、上限选择。 */
    private void buildOptionsCard(LinearLayout col) {
        LinearLayout card = addCard(col, "调节方式与安全选项");

        // 浮球
        swKnob = newSwitch();
        swKnob.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton button, boolean checked) {
                if (isSuppressed()) return;
                state.setKnobEnabled(checked);
                refreshKnobInService();
                if (!DimService.isRunning()) toast("启用遮罩后浮球才会出现");
            }
        });
        addSwitchRow(card, "浮球调节",
                "屏幕上显示一个可拖动的圆球：上下拖动调节不透明度，双击开关遮罩，长按隐藏浮球。"
                        + "亮度超过 80%（普通模式）时会强制显示安全浮球，防止触摸被遮罩拦住。",
                swKnob);

        // 安全触摸上限
        swSafe = newSwitch();
        swSafe.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton button, boolean checked) {
                if (isSuppressed()) return;
                if (checked) {
                    state.setSafeTouch(true);
                    refreshKnobInService();
                    syncOpacityUi(state.effectiveOpacity());
                    toast("已限制在 80% 以内，触摸可正常透传");
                } else {
                    confirmDisableSafeTouch();
                }
            }
        });
        addSwitchRow(card, "安全触摸上限 80%",
                "Android 12+ 规定：普通模式下全屏悬浮窗不透明度超过 80% 时，对下层应用的触摸会被系统拦截。"
                        + "开启此项后上限锁在 80%；要选 95% 必须先把这里关掉（或改用下面的深度模式）。",
                swSafe);

        // 深度模式：普通遮罩固定 ≤80%（触摸安全），差额由无障碍图层（trusted overlay）补足
        swDeep = newSwitch();
        deepListener = new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton button, boolean checked) {
                if (isSuppressed()) return;
                if (!checked) {
                    // 关闭：立即落盘，服务会把超过 80% 的值夹回去
                    pendingDeep = false;
                    state.setDeepMode(false);
                    clampOpacityToEffectiveMax();
                    refreshKnobInService();
                    refreshDeepModeUi();
                    syncOpacityUi(state.effectiveOpacity());
                    toast("已关闭深度模式，上限恢复到普通遮罩");
                    return;
                }
                if (state.isDeepConnected()) {
                    // 无障碍已连接：立即落盘生效
                    pendingDeep = false;
                    state.setDeepMode(true);
                    clampOpacityToEffectiveMax();
                    refreshKnobInService();
                    refreshDeepModeUi();
                    syncOpacityUi(state.effectiveOpacity());
                    return;
                }
                // 未连接：先不落盘（避免永久记住一个无效开关），等 onResume 兜底确认
                pendingDeep = true;
                toast("请先在系统「无障碍」里开启「极暗」");
                openAccessibilitySettings();
                refreshDeepModeUi();
            }
        };
        swDeep.setOnCheckedChangeListener(deepListener);
        tvDeepStatus = addSwitchRow(card, "深度模式（进阶）", deepModeText(), swDeep);
        addDeepNote(card);

        // 实验模式
        swDimBehind = newSwitch();
        swDimBehind.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton button, boolean checked) {
                if (isSuppressed()) return;
                state.setDimBehind(checked);
                refreshKnobInService();
                toast(checked ? "已切换为系统暗度层，请观察是否生效" : "已切回黑色遮罩窗口");
            }
        });
        addSwitchRow(card, "实验：系统暗度层",
                "改用 FLAG_DIM_BEHIND 压暗，不依赖窗口透明度，可能绕开 80% 触摸限制，需真机验证（部分机型无效）。",
                swDimBehind);

        // 上限选择
        TextView maxTitle = labelView("可调上限", 14, C_TEXT, false);
        LinearLayout.LayoutParams mtLp = lpMatch();
        mtLp.topMargin = dp(18);
        card.addView(maxTitle, mtLp);

        LinearLayout maxRow = row();
        for (int i = 0; i < MAX_CHOICES.length; i++) {
            final int value = MAX_CHOICES[i];
            TextView btn = makeButton(value + "%", false);
            btn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onMaxChosen(value);
                }
            });
            maxViews[i] = btn;
            LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) bLp.leftMargin = dp(8);
            maxRow.addView(btn, bLp);
        }
        LinearLayout.LayoutParams mrLp = lpMatch();
        mrLp.topMargin = dp(10);
        card.addView(maxRow, mrLp);

        TextView maxDesc = labelView(
                "上限越高越暗。普通模式下超过 80% 触摸会被系统拦截，只有关闭安全触摸或开启深度模式才能真的用上 85% 以上。",
                11, C_SUB, false);
        LinearLayout.LayoutParams mdLp = lpMatch();
        mdLp.topMargin = dp(8);
        card.addView(maxDesc, mdLp);
    }

    /** 权限卡片：每行状态 + 按钮。 */
    private void buildPermissionCard(LinearLayout col) {
        LinearLayout card = addCard(col, "权限与后台保活");

        rowOverlay = addPermissionRow(card, "悬浮窗权限",
                "应用信息 → 其他权限 → 显示在其他应用上层。没有它遮罩无法显示。",
                "去授权", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openOverlaySettings();
                    }
                });

        rowNotification = addPermissionRow(card, "通知权限",
                "前台服务需要常驻通知，锁屏上可通过通知按钮快速关闭遮罩。",
                "去授权", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        requestNotificationPermission();
                    }
                });

        rowBattery = addPermissionRow(card, "电池优化",
                "加入「不优化」白名单，避免系统在后台把你杀掉。",
                "去设置", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openBatterySettings();
                    }
                });

        rowAutostart = addPermissionRow(card, "后台自启动（HyperOS）",
                "红米 / HyperOS 需要开启「自启动」并设置「省电策略：无限制」，否则重启或清后台后遮罩会消失。",
                "去设置", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openAutostartSettings();
                    }
                });
    }

    /** 使用说明卡片。 */
    private void buildHelpCard(LinearLayout col) {
        LinearLayout card = addCard(col, "使用说明");
        addBullet(card, "控制中心磁贴：下拉控制中心 → 编辑 → 添加「极暗」，之后一键开关遮罩。");
        addBullet(card, "悬浮窗权限路径：设置 → 应用信息 → 其他权限 → 显示在其他应用上层。");
        addBullet(card, "锁屏、息屏显示（AOD）由系统绘制在遮罩之上，可能无法压暗。");
        addBullet(card, "普通模式下，状态栏、导航栏、输入法由系统绘制在遮罩之上，无法被压暗；"
                + "开启「深度模式」后可以覆盖（无障碍图层层级高于状态栏）。");
        addBullet(card, "极暗不改变系统亮度值，因此不会影响自动亮度 / 护眼模式的调节。");
        addBullet(card, "遮罩不能省电：它只降低像素亮度，屏幕背光依旧工作。");
        addBullet(card, "微信 / 支付等应用的屏幕截图或录屏会看到遮罩，属于正常现象。");
    }

    /** 底部设备信息。 */
    private void buildFooter(LinearLayout col) {
        TextView device = labelView(
                "设备：" + Build.MANUFACTURER + " " + Build.MODEL, 11, C_SUB, false);
        LinearLayout.LayoutParams dlp = lpMatch();
        dlp.topMargin = dp(18);
        col.addView(device, dlp);

        TextView version = labelView(
                "Android " + Build.VERSION.RELEASE + "（SDK " + Build.VERSION.SDK_INT + "）",
                11, C_SUB, false);
        LinearLayout.LayoutParams vlp = lpMatch();
        vlp.topMargin = dp(4);
        col.addView(version, vlp);

        String versionName = "1.0";
        try {
            String name = getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName;
            if (name != null && name.length() > 0) versionName = name;
        } catch (Throwable ignored) {
        }
        TextView app = labelView("极暗 UltraDim v" + versionName, 11, C_SUB, false);
        LinearLayout.LayoutParams alp = lpMatch();
        alp.topMargin = dp(4);
        col.addView(app, alp);

        // 署名 / 项目链接：点一下用浏览器打开项目主页（LICENSE 要求分发时保留此链接）
        TextView author = labelView(getString(R.string.author_line), 11, C_ACCENT, false);
        author.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openProjectPage();
            }
        });
        LinearLayout.LayoutParams aulp = lpMatch();
        aulp.topMargin = dp(6);
        col.addView(author, aulp);

        TextView license = labelView(getString(R.string.license_line), 10, C_SUB, false);
        LinearLayout.LayoutParams llp2 = lpMatch();
        llp2.topMargin = dp(4);
        col.addView(license, llp2);

        // 免责声明与非官方声明：LICENSE 同款，放在用户能看到的地方
        TextView disclaimer = labelView(getString(R.string.disclaimer_line), 10, C_SUB, false);
        LinearLayout.LayoutParams dlp2 = lpMatch();
        dlp2.topMargin = dp(8);
        col.addView(disclaimer, dlp2);

        TextView unofficial = labelView(getString(R.string.unofficial_line), 10, C_SUB, false);
        LinearLayout.LayoutParams ulp = lpMatch();
        ulp.topMargin = dp(4);
        col.addView(unofficial, ulp);
    }

    /** 打开项目主页；没有浏览器时退回提示，避免静默失败。 */
    private void openProjectPage() {
        String url = getString(R.string.project_url);
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Throwable t) {
            toast("无法打开浏览器，项目地址：" + url);
        }
    }

    // ------------------------------------------------------------------
    // 交互逻辑
    // ------------------------------------------------------------------

    /** 打开「启用遮罩」开关。 */
    private void enableDim() {
        if (!Settings.canDrawOverlays(this)) {
            toast("请先授予「显示在其他应用上层」权限");
            setSwitchSilently(swEnable, false);
            openOverlaySettings();
            return;
        }
        state.setEnabled(true);
        DimService.start(this);
        // 乐观更新：开关已由点击切到「开」，状态胶囊也立刻跟随，之后仅靠 ACTION_STATE 广播校准
        applyRunningState(true);
    }

    /**
     * 统一的调节入口。
     *
     * @param value    目标不透明度，会被夹到 0..effectiveMaxOpacity
     * @param fromUser 是否用户主动操作（用于「先启用遮罩」提示）
     */
    private void changeOpacity(int value, boolean fromUser) {
        int clamped = state.clampToEffectiveMax(value);
        state.setOpacity(clamped);
        if (DimService.isRunning()) {
            DimService.setOpacity(this, clamped);
        } else if (fromUser) {
            // 2 秒节流：连续点 ±1% / 预设时不会每次都弹，但也不再用永久标志
            long now = System.currentTimeMillis();
            if (now - lastHintAt > 2000) {
                lastHintAt = now;
                toast("先启用遮罩，调节才会生效");
            }
        }
        syncOpacityUi(clamped);
    }

    /** 只刷新不透明度相关控件，不回写 State。 */
    private void syncOpacityUi(int value) {
        final int clamped = State.clamp(value, 0, state.effectiveMaxOpacity());
        silently(new Runnable() {
            @Override
            public void run() {
                seek.setMax(state.effectiveMaxOpacity());
                seek.setProgress(clamped);
            }
        });
        tvPercent.setText(clamped + "%");
        tvPercent.setTextColor(clamped >= 70 ? C_WARN : C_TEXT);
        tvMaxHint.setText("当前上限 " + state.effectiveMaxOpacity()
                + "%（安全触摸：" + (state.isSafeTouch() ? "开" : "关")
                + (state.isDeepActive() ? " · 深度模式已生效" : "") + "）");
        updateMaxButtons();
    }

    /** 上限按钮点击。 */
    private void onMaxChosen(int value) {
        state.setMaxOpacity(value);
        if (state.getOpacity() > state.effectiveMaxOpacity()) {
            state.setOpacity(state.effectiveMaxOpacity());
        }
        if (DimService.isRunning()) {
            DimService.send(this, DimService.ACTION_APPLY);
        }
        syncOpacityUi(state.effectiveOpacity());
        toast("可调上限已设为 " + value + "%");
    }

    /** 关闭安全触摸前的二次确认。 */
    private void confirmDisableSafeTouch() {
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("关闭安全触摸上限？")
                .setMessage("关闭后遮罩可以超过 80%。在 Android 12+ 上，"
                        + "当全屏悬浮窗不透明度超过 80% 时，触摸可能无法透传到下层应用，"
                        + "可能导致屏幕完全无法操作，只能靠浮球或通知按钮救回。\n\n确定要关闭吗？")
                .setPositiveButton("仍然关闭", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        state.setSafeTouch(false);
                        refreshKnobInService();
                        syncOpacityUi(state.effectiveOpacity());
                        toast("已解锁 80% 以上，操作失控时请用浮球或通知关闭");
                    }
                })
                .setNegativeButton("取消", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        setSwitchSilently(swSafe, true);
                    }
                })
                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                    @Override
                    public void onCancel(DialogInterface dialog) {
                        setSwitchSilently(swSafe, true);
                    }
                })
                .show();
    }

    /** 通知服务应用新的 State（浮球显隐、暗度模式、上限等）。 */
    private void refreshKnobInService() {
        if (DimService.isRunning()) {
            DimService.send(this, DimService.ACTION_APPLY);
        }
    }

    // ------------------------------------------------------------------
    // 刷新
    // ------------------------------------------------------------------

    private void refreshAll() {
        refreshStatus();
        refreshSwitches();
        refreshDeepModeUi();
        syncOpacityUi(state.effectiveOpacity());
        refreshPermissions();
    }

    private void refreshStatus() {
        applyRunningState(DimService.isRunning());
    }

    private void applyRunningState(boolean running) {
        setSwitchSilently(swEnable, running);
        tvStatus.setText(running ? "● 运行中" : "● 已关闭");
        tvStatus.setTextColor(running ? C_ACCENT : C_SUB);
        tvStatus.setBackground(roundedBackground(
                running ? 0x225B8DEF : 0x1FFFFFFF, 0, 20));
    }

    private void refreshSwitches() {
        silently(new Runnable() {
            @Override
            public void run() {
                swKnob.setChecked(state.isKnobEnabled());
                swSafe.setChecked(state.isSafeTouch());
                swDimBehind.setChecked(state.isDimBehind());
            }
        });
    }

    /**
     * 深度模式文案（四种状态）：
     * 未开启 / 已连接且图层生效 / 已连接但图层建不出来（回退 80%）/ 开了开关但没连上。
     */
    private String deepModeText() {
        if (!state.isDeepMode()) {
            return "可突破 80% 且触摸仍可用（需开启无障碍服务，可选）";
        }
        if (state.isDeepActive()) {
            if (DimService.isDeepLayerOk()) {
                return "✅ 已生效：上限放开到 " + state.effectiveMaxOpacity() + "%，触摸仍然可用";
            }
            // 无障碍连上了，但悬浮窗图层 addView 被系统/OEM 拒绝：实际已回退 80%，
            // 不能宣称生效，否则用户会遇到「滑动调不暗」的静默失效。
            return "⚠ 无障碍已连接，但压暗图层创建失败（已回退 80%）。"
                    + "请到系统设置给「极暗」开启「显示在其他应用上层」权限后重试";
        }
        return "⚠ 未连接：请在系统设置「无障碍」里开启「极暗」";
    }

    /** 只改开关视觉、不触发业务回调（摘掉监听器 → setChecked → 挂回）。 */
    private void setDeepSwitchChecked(boolean checked) {
        if (swDeep == null || swDeep.isChecked() == checked) return;
        swDeep.setOnCheckedChangeListener(null);
        swDeep.setChecked(checked);
        swDeep.setOnCheckedChangeListener(deepListener);
    }

    /** 刷新深度模式开关与状态文案（从无障碍设置返回时也要调用）。 */
    private void refreshDeepModeUi() {
        if (swDeep == null) return;
        // 未落盘（不存在 / 未连接）时把开关视觉拨回关闭，避免开关与真实状态不一致
        setDeepSwitchChecked(state.isDeepMode());
        if (tvDeepStatus != null) {
            tvDeepStatus.setText(deepModeText());
            boolean ok = state.isDeepActive() && DimService.isDeepLayerOk();
            tvDeepStatus.setTextColor(ok ? C_OK
                    : (state.isDeepMode() ? C_WARN : C_SUB));
        }
    }

    /** 上限变化（深度模式开关 / 上限选择）后把当前不透明度夹回允许区间。 */
    private void clampOpacityToEffectiveMax() {
        int max = state.effectiveMaxOpacity();
        if (state.getOpacity() > max) {
            state.setOpacity(max);
        }
    }

    private void updateMaxButtons() {
        int current = state.getMaxOpacity();
        for (int i = 0; i < MAX_CHOICES.length; i++) {
            boolean selected = current == MAX_CHOICES[i];
            TextView view = maxViews[i];
            if (view == null) continue;
            view.setBackground(selected
                    ? roundedBackground(C_ACCENT, 0, 20)
                    : roundedBackground(Color.TRANSPARENT, C_BTN_LINE, 20));
            view.setTextColor(selected ? Color.WHITE : C_TEXT);
        }
    }

    private void refreshPermissions() {
        boolean overlay = Settings.canDrawOverlays(this);
        setPermissionText(rowOverlay, overlay, "悬浮窗权限");

        boolean notification = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
        setPermissionText(rowNotification, notification, "通知权限");

        boolean battery = false;
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            battery = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Throwable ignored) {
        }
        setPermissionText(rowBattery, battery, "电池优化");

        if (rowAutostart != null) {
            rowAutostart.setText("⚠ 后台自启动（需手动检查）");
            rowAutostart.setTextColor(C_WARN);
        }
    }

    private void setPermissionText(TextView view, boolean granted, String name) {
        if (view == null) return;
        view.setText((granted ? "✅ " : "❌ ") + name);
        view.setTextColor(granted ? C_OK : C_WARN);
    }

    // ------------------------------------------------------------------
    // 跳转权限页
    // ------------------------------------------------------------------

    private void openOverlaySettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Throwable t) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
            } catch (Throwable t2) {
                toast("无法打开悬浮窗设置，请手动到系统设置中授权");
            }
        }
    }

    private void requestNotificationPermission() {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            toast("通知权限已授予");
            refreshPermissions();
            return;
        }
        try {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQ_NOTIFICATIONS);
        } catch (Throwable t) {
            openAppDetailsSettings();
        }
    }

    private void openBatterySettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Throwable t) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Throwable t2) {
                openAppDetailsSettings();
            }
        }
    }

    private void openAutostartSettings() {
        try {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName("com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"));
            startActivity(intent);
        } catch (Throwable t) {
            openAppDetailsSettings();
        }
    }

    /** 跳系统无障碍设置（深度模式需要手动开启无障碍服务）。 */
    private void openAccessibilitySettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Throwable t) {
            try {
                Intent fallback = new Intent(Settings.ACTION_SETTINGS);
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(fallback);
            } catch (Throwable t2) {
                toast("无法打开无障碍设置，请手动进入系统设置开启");
            }
        }
    }

    private void openAppDetailsSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Throwable t) {
            toast("请手动在系统设置中找到「极暗」");
        }
    }

    // ------------------------------------------------------------------
    // 控件工厂
    // ------------------------------------------------------------------

    private TextView labelView(String text, float sizeSp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    private TextView makeButton(String label, boolean filled) {
        TextView view = labelView(label, 13, filled ? Color.WHITE : C_TEXT, true);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(12), dp(10), dp(12), dp(10));
        view.setBackground(filled
                ? roundedBackground(C_ACCENT, 0, 10)
                : roundedBackground(0x14FFFFFF, C_BTN_LINE, 10));
        view.setClickable(true);
        view.setFocusable(true);
        return view;
    }

    private Switch newSwitch() {
        Switch view = new Switch(this);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private static LinearLayout.LayoutParams lpMatch() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private GradientDrawable roundedBackground(int fill, int stroke, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radiusDp));
        if (stroke != 0) drawable.setStroke(Math.max(1, dp(1)), stroke);
        return drawable;
    }

    /** 卡片容器：16dp 圆角、卡片色填充、1dp 边框。 */
    private LinearLayout addCard(LinearLayout col, String title) {
        LinearLayout card = column();
        card.setBackground(roundedBackground(C_CARD, C_BORDER, 16));
        int pad = dp(16);
        card.setPadding(pad, pad, pad, pad);

        LinearLayout.LayoutParams cardLp = lpMatch();
        cardLp.topMargin = dp(14);
        col.addView(card, cardLp);

        if (title != null) {
            TextView header = labelView(title, 15, C_TEXT, true);
            LinearLayout.LayoutParams headerLp = lpMatch();
            headerLp.bottomMargin = dp(8);
            card.addView(header, headerLp);
        }
        return card;
    }

    /** 开关行：标题 + 右侧 Switch，下方说明文字。返回说明 TextView 供动态刷新（可为 null）。 */
    private TextView addSwitchRow(LinearLayout card, String title, String desc, Switch switchView) {
        LinearLayout line = row();
        TextView titleView = labelView(title, 14, C_TEXT, false);
        line.addView(titleView, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        line.addView(switchView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams lineLp = lpMatch();
        lineLp.topMargin = dp(12);
        card.addView(line, lineLp);

        TextView descView = null;
        if (desc != null && desc.length() > 0) {
            descView = labelView(desc, 12, C_SUB, false);
            descView.setLineSpacing(dp(3), 1f);
            LinearLayout.LayoutParams descLp = lpMatch();
            descLp.topMargin = dp(4);
            card.addView(descView, descLp);
        }
        return descView;
    }

    /** 深度模式的固定补充说明：强调可选、需要无障碍权限、不读取屏幕内容。 */
    private void addDeepNote(LinearLayout card) {
        TextView note = labelView(
                "可选进阶功能。只额外叠加一层黑色图层，不会读取屏幕内容；"
                        + "不需要时可随时关闭，关闭后上限恢复到普通遮罩。",
                11, C_SUB, false);
        note.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams noteLp = lpMatch();
        noteLp.topMargin = dp(6);
        card.addView(note, noteLp);
    }

    /** 权限行：左侧标题（含 ✅/❌）、右侧按钮，下方说明。返回标题 TextView 供刷新。 */
    private TextView addPermissionRow(LinearLayout card, String title, String desc,
                                      String action, View.OnClickListener listener) {
        LinearLayout box = column();

        LinearLayout line = row();
        TextView titleView = labelView(title, 14, C_TEXT, false);
        line.addView(titleView, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView button = makeButton(action, false);
        button.setOnClickListener(listener);
        line.addView(button, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        box.addView(line, lpMatch());

        if (desc != null && desc.length() > 0) {
            TextView descView = labelView(desc, 12, C_SUB, false);
            descView.setLineSpacing(dp(3), 1f);
            LinearLayout.LayoutParams descLp = lpMatch();
            descLp.topMargin = dp(4);
            box.addView(descView, descLp);
        }

        LinearLayout.LayoutParams boxLp = lpMatch();
        boxLp.topMargin = dp(14);
        card.addView(box, boxLp);
        return titleView;
    }

    private void addBullet(LinearLayout card, String text) {
        TextView view = labelView("· " + text, 12, C_SUB, false);
        view.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams lp = lpMatch();
        lp.topMargin = dp(8);
        card.addView(view, lp);
    }

    private void setSwitchSilently(CompoundButton button, boolean checked) {
        if (button == null) return;
        final CompoundButton target = button;
        final boolean value = checked;
        silently(new Runnable() {
            @Override
            public void run() {
                target.setChecked(value);
            }
        });
    }

    /** 是否正处于「代码改控件」的抑制区间（计数式，嵌套调用不会提前解除）。 */
    private boolean isSuppressed() {
        return suppressDepth > 0;
    }

    /** 在抑制区间内执行一段改控件状态的代码，期间监听器直接返回。 */
    private void silently(Runnable action) {
        suppressDepth++;
        try {
            action.run();
        } finally {
            suppressDepth--;
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String text) {
        try {
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }
}
