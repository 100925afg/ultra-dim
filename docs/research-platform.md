# Android 14+ 全屏遮罩降亮度：平台机制调研

- 目标机型：REDMI Turbo 4，HyperOS 3.0.304.0 / Android 16（API 36）
- 调研时间：2026-10；AOSP 依据取自 `refs/heads/main`（行号可能随分支漂移，函数名为准）
- 方法：AOSP 源码（android.googlesource.com，`?format=TEXT` 抓取并解码）+ Android 官方文档 + GitHub/社区实测反馈
- 标记约定：✅ 已有源码/官方文档确证；⚠️ 需真机验证（HyperOS 可能改行为）

---

## 1. 不可信触摸遮挡阈值 ✅

| 项 | 结论 |
|---|---|
| 阈值常量 | `MAXIMUM_OBSCURING_OPACITY_FOR_TOUCH`，默认 **0.8** |
| 默认值定义 | `InputSettings.DEFAULT_MAXIMUM_OBSCURING_OPACITY_FOR_TOUCH = .8f`（`services/core/java/com/android/server/input/InputSettings.java` ~L96） |
| 判据 | `InputDispatcher.cpp` ~L3238：`if (occlusionInfo.obscuringOpacity > mMaximumObscuringOpacityForTouch)` |
| 边界 | **严格大于才拦截 → 0.8 可过，0.8000001 会被拦**（浮点比较，建议留余量用 0.79） |
| 可配置 | `Settings.Global "maximum_obscuring_opacity_for_touch"`（`Settings.java` ~L19405，`@hide/@Readable`），只增不减（默认取 max(0.8, 值)），改它需 `INTERNAL_SYSTEM_WINDOW`/`INPUT_MANAGER` 级别权限，普通应用改不了 |
| 官方说明 | `WindowManager.java` FLAG_NOT_TOUCHABLE javadoc（~L2726-2778）："combined obscuring opacity ... must be less than or equal to 0.8" |

补充机制（决定架构）：
- 判定按**窗口 frame**而非 touchable region：`InputDispatcher.cpp` ~L609 `windowOccludesTouchAt()` 只用 `windowInfo.frame` → 一个全屏 FLAG_NOT_TOUCHABLE 的窗口同样计入遮挡。
- **同 UID 多窗口累乘**：~L3194-3206 `opacity = 1 - (1-opacity)*(1-otherAlpha)`（按 uid 聚合）→ 开两个 0.5 的窗口 = 0.75，再一个 0.5 = 0.875 被拦；`canBeObscuredBy` ~L3115 对同 uid 不做豁免（信任判断在 WMS 侧，见 §4）。
- 被拦后果：触摸事件被丢弃并打印 `Untrusted touch due to occlusion by <pkg>/<window>`；拖拽/多指滑动可能整段失效。

来源：
- https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/inputflinger/dispatcher/InputDispatcher.cpp
- https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/input/InputSettings.java
- https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/view/WindowManager.java

---

## 2. 遮挡不透明度取自哪个 alpha ✅

**只取 `WindowManager.LayoutParams.alpha`（整个窗口的合成 alpha），不取 View 像素/背景 ARGB/Surface alpha。**

- 官方 javadoc（`WindowManager.java` FLAG_NOT_TOUCHABLE 段）："the opacity of the window is defined by `WindowManager.LayoutParams.alpha`"。
- WMS 侧：`WindowState` 的 `WindowStateAnimator.mAlpha` 由 `LayoutParams.alpha`（及窗口动画）驱动，再写入 `InputWindowInfo.alpha`：
  - `WindowState.java`（14/main）`mWinAnimator.mAlpha = a.alpha`；`InputMonitor`/`updateInputChannel` 组装 `InputWindowHandle`。
  - `InputDispatcher.cpp` ~L3194-3206 用 `InputWindowInfo.alpha` 计算遮挡。
- 实测结论（社区一致，⚠️ 真机复核）：**`params.alpha = 1.0` + 背景纯黑（ARGB 的 A 随便设 0.5/0.2）= 按 100% 遮挡处理 → 触摸被吞**。要让触摸透传，必须让 `params.alpha` 本身 ≤0.8（建议 0.79）。
- 反过来：`params.alpha=0.3` + 黑色背景 = 视觉上 30% 黑、遮挡按 30% 计，可放心触摸透传。

⚠️ 待真机确认：HyperOS 是否在 `alpha` 之外额外参考 HWC 合成结果（理论上不会）。

来源：同上 WindowManager.java / InputDispatcher.cpp。

---

## 3. FLAG_DIM_BEHIND + dimAmount ✅（机制）+ ⚠️（厂商上限）

- `dimAmount` 文档：`Range is from 1.0 for completely opaque to 0.0 for no dim`，默认 1.0（`WindowManager.java` LayoutParams）。
- 实现：`Dimmer`（`services/core/java/com/android/server/wm/Dimmer.java`，Android 13+ 为 `DimmerAnimationHelper`+`DimState`）**直接创建 SurfaceControl 纯色层**（`.setColorLayer()`，名字 "Dim Layer for - <host>"），挂在宿主 WindowContainer 的 surface 下、相对定位在该窗口之下（`makeDimLayer()` ~L168-175，`adjustAppearance()` ~L218）。
  - 结论：**dim 层不是 Window，没有 InputChannel/InputWindowInfo → 完全不参与"不可信触摸"遮挡计算**。这就是"既压暗又让触碰透传"的合法通路。
- 有没有上限：AOSP 侧未发现对 `dimAmount` 的 clamp（直接透传给 SurfaceControl alpha，SurfaceFlinger 侧按 [0,1] 截断）。但 **HWC/厂商可能限制 dim 强度**（尤其"极暗"类场景），⚠️ 需真机实测（建议在 0.8/0.9/1.0 三档看 logcat + 实际观感）。
- 覆盖范围：dim 层挂在宿主容器（通常是你所在 Task/display area）且 z 在请求窗口之下 → **只压暗该窗口之下的层**。全屏应用 overlay（layer 11）的 dim 覆盖不到状态栏(15)/导航栏(24)/IME(13)。⚠️ HyperOS 可能对 overlay 窗口的 dim 特殊处理。
- 推荐组合（架构关键）：
  1. **透明窗 + dim**：`params.alpha≈0`（或窗口完全透明只留 dim）+ `FLAG_DIM_BEHIND` + `dimAmount=0.9~1.0` + `FLAG_NOT_TOUCHABLE` → 深度压暗、触摸完全不拦、但状态栏区域不暗。
  2. **不透明度窗**：`params.alpha=0.79` + 纯黑背景 + `FLAG_NOT_TOUCHABLE` → 全屏（含状态栏区域之外）均匀压暗 ~79%，触摸可用。
  3. **无障碍 trusted overlay**：`TYPE_ACCESSIBILITY_OVERLAY` + `params.alpha` 任意（甚至 0.95）→ 因为 trusted 不参与遮挡，可压到 95%+ 且触摸可用，并且层级高于状态栏/导航栏（§4、§8）。

来源：Dimmer.java https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/wm/Dimmer.java

---

## 4. TYPE_ACCESSIBILITY_OVERLAY 是否绕过限制 ✅ 可行（第三方普通应用）

**结论：可行，且不需要 system 签名、不需要 SYSTEM_ALERT_WINDOW，只需声明 BIND_ACCESSIBILITY_SERVICE 并由用户在设置里开启无障碍。窗口天然是 trusted overlay，永不参与遮挡计算，可把屏幕压到任意暗度同时触摸可用。**

源码依据（main）：
- trusted 判定是**按窗口类型**的：`WindowState.shouldWindowHandleBeTrusted()`（14）/~`isWindowTrustedOverlay()`（main）：
  `return InputMonitor.isTrustedOverlay(mAttrs.type) || ((PRIVATE_FLAG_TRUSTED_OVERLAY) && s.mCanAddInternalSystemWindow) || ((PRIVATE_FLAG_SYSTEM_APPLICATION_OVERLAY) && s.mCanCreateSystemApplicationOverlay);`
  → 第一项命中即 trusted，**不需要 private flag，也不需要系统签名**。
- `InputMonitor.isTrustedOverlay(int type)` 白名单含 `TYPE_ACCESSIBILITY_OVERLAY`（还有 IME、状态栏、导航栏、SECURE_SYSTEM_OVERLAY 等）；`TYPE_APPLICATION_OVERLAY` **不在**其中。
- 权限来源不是 SAW 而是 a11y token：`WindowManagerService.sanitizeWindowType()`（~L10236-10265）—— `type==TYPE_ACCESSIBILITY_OVERLAY && windowToken != null` 时要求 token 类型匹配（"Only accessibility services can add accessibility overlays"）；`addWindow` ~L1716 也校验 `token.windowType == TYPE_ACCESSIBILITY_OVERLAY`。
- 客户端正规写法（官方 API 文档明确提供）：`AccessibilityService.createWindowContext(TYPE_ACCESSIBILITY_OVERLAY, null)` 或者对 display 的 `createWindowContext(display, TYPE_ACCESSIBILITY_OVERLAY, null)`，再从该 Context 取 `WindowManager` addView；`AccessibilityService` 内部 `wm.setDefaultToken(mWindowToken)` / `connection.getOverlayWindowToken(displayId)` 自动注入 token。文档示例：https://developer.android.com/reference/android/accessibilityservice/AccessibilityService （createWindowContext 段落，含 overlay 示例代码）
- 层级：`WindowManagerPolicy.getWindowLayerFromTypeLw`：`TYPE_ACCESSIBILITY_OVERLAY` = **31**，高于状态栏(15)、通知栏/QS(17)、keyguard dialog(19)、导航栏(24)、IME(13)（详见 §8 表）。→ 能盖住状态栏/导航栏。
- 限制/风险（⚠️ 真机验证）：
  - 用户必须开无障碍；HyperOS 会**随机/静默关闭无障碍服务**（见 §6），需引导用户加白名单。
  - 其他应用调用 `Window.setHideOverlayWindows(true)`（需 `HIDE_OVERLAY_WINDOWS`）会隐藏"非系统 overlay"；trusted overlay 理论上不被隐藏（`PRIVATE_FLAG_SYSTEM_APPLICATION_OVERLAY` 的 javadoc 说明该类窗口"stay visible, even if SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS is set"），⚠️ 实测。
  - 无障碍开启时系统会显示"无障碍服务正在运行"提示；Google Play 对无障碍用途审查严格（侧载/自用无影响）。
  - 部分机型对 a11y overlay 尺寸/触摸有额外校验（如禁止 touchable 全屏），本应用只用 FLAG_NOT_TOUCHABLE，风险低。

来源：WindowState.java / InputMonitor.java / WindowManagerService.java / WindowManagerPolicy.java（均 refs/heads/main）。

---

## 5. 前台服务（Android 14/15/16）✅

**建议 `foregroundServiceType="specialUse"`**（覆盖层/降亮度不属于任何标准类型；`dataSync` 在 Android 15 有 6h/24h 超时，不能选）。

Manifest 要点：
```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE"/>
<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>   <!-- 13+，不强制但没通知=隐形服务 -->
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED"/> <!-- 需要开机自启时 -->
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW"/>   <!-- 仅 TYPE_APPLICATION_OVERLAY 方案需要 -->

<service android:name=".DimService"
         android:foregroundServiceType="specialUse"
         android:exported="false">
  <property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
            android:value="full-screen dimming overlay"/>
</service>
```
- Android 14（targetSdk 34+）：必须声明类型 + 对应 `FOREGROUND_SERVICE_*` 权限，否则 `MissingForegroundServiceTypeException`。specialUse 还需 `<property>` 子类型说明。⚠️ specialUse 在 Play 上架需审核理由（侧载无关）。
- Android 15（targetSdk 35+）两条硬限制：
  1. **BOOT_COMPLETED 启动 FGS** 被禁的类型：dataSync/camera/mediaPlayback/phoneCall/mediaProjection/microphone；**specialUse 不在禁列 → 可用**（仍需 HyperOS 自启动白名单）。
  2. **持有 SYSTEM_ALERT_WINDOW 时从后台起 FGS 收紧**：必须"先有可见的 TYPE_APPLICATION_OVERLAY 窗口，再从后台 startForeground"，否则 `ForegroundServiceStartNotAllowedException`。
     → 正确顺序：`addView(overlay)` →（窗口可见）→ `startForeground(...)`。这也意味着 overlay 方案不能靠 FGS 保活来"先起服务后加窗"。
  3. dataSync/mediaProcessing 新增 `Service.onTimeout()`。
  （https://developer.android.com/about/versions/15/behavior-changes-15）
- Android 16（API 36）：行为变更页未新增与本场景相关的 FGS/overlay 限制（健康权限、predictive back 等无关项）。（https://developer.android.com/about/versions/16/behavior-changes-16）
- 若采用**纯无障碍 overlay 方案**：可完全不申请 SYSTEM_ALERT_WINDOW → 规避上面 15 的第 2 条，也不受"显示在其他应用上层"权限被 HyperOS 收走的困扰；但必须维持无障碍开启。

---

## 6. HyperOS / MIUI 实际坑 ⚠️（多为社区实测，非源码）

权限/设置入口（HyperOS 3 大致路径，逐机型略有差异）：
- **显示在其他应用上层**：设置 → 应用设置 → 应用管理 → 本应用 → 权限管理 → 「显示在其他应用上层」（旧 MIUI 叫「显示悬浮窗」）。无此权限 `addView(TYPE_APPLICATION_OVERLAY)` 直接抛 `BadTokenException`/被静默丢弃。
- **后台弹出界面**（MIUI 专有）：权限管理内单独一项，控制"后台启动 Activity/弹窗"，弹设置向导/授权页时常被拦。
- **自启动**：设置 → 应用设置 → 授权管理 → 自启动。开机恢复 overlay 必备（否则 BOOT_COMPLETED 收不到）。
- **省电策略**：应用信息 → 省电策略 → **无限制**（默认"智能省电"会在熄屏/后台数分钟后杀服务）。
- **锁屏显示**：应用信息 → 其他权限 → 「锁屏显示」（部分 HyperOS 版本保留），否则锁屏上不显示本应用窗口。
- **无障碍被静默关闭**（HyperOS 已知问题，与 AOSP 行为叠加）：社区有专门修复模块/守护 App：
  - https://github.com/chkndrp/hyperos-accessibility-fix （README 引 AOSP `AccessibilityManagerService.onPackagesForceStoppedLocked`：应用被 force-stop 时系统会**擦除其无障碍权限**；HyperOS 硬编码在进入"超级省电"等场景 force-stop 应用，logcat 可见 `Force stopping com.urbandroid.lux ... LockScreenClean` —— Lux 正是一款屏幕调光 App，直接同场景案例）
  - https://github.com/haku4130/hyperos-accessibility-guard
- 后台清理：安全中心"清理"、多任务上划、锁屏清理（LockScreenClean）会杀进程；overlay 走 FGS + 省电无限制 + 自启动才稳。
- 遮挡范围（⚠️ 必须真机验证）：
  - **下拉通知栏 / 控制中心**：系统层（layer 17）在 app overlay(11) 之上 → 遮罩被通知栏盖住，通知栏区域"变亮"。HyperOS 半透明通知栏可能透出后面的遮罩。
  - **多任务/最近任务**：HyperOS 桌面为普通 Activity，app overlay(11) 层级更高，通常仍可见；但 HyperOS 可能对 overlay 做特殊隐藏（部分 MIUI 版本在任务切换动画期间隐藏 overlay）。
  - **锁屏**：keyguard 相关窗口（17/19）高于 app overlay → 锁屏上不可见；需「锁屏显示」权限且仍可能被 keyguard 覆盖。
  - 挂断/来电、指纹/支付界面同理（系统层更高）。
- 用户实测帖参考：https://www.zhihu.com/question/525943762 （"手机dc调光的软件/屏幕滤镜app"讨论，涉及小米上的遮罩类 App 与权限问题）；https://github.com/chkndrp/hyperos-accessibility-fix （HyperOS 杀无障碍实测）
- 额外坑：HyperOS「MIUI 优化」开关、游戏加速/性能模式可能改变 overlay 合成；深色模式/护眼模式与遮罩叠加会被用户误认为"更暗"。

---

## 7. 极暗 / Extra dim（reduce_bright_colors）✅ 可写，⚠️ 效果需真机

可由普通应用经 `WRITE_SECURE_SETTINGS` 写入（该权限 protectionLevel 含 `development`，`adb shell pm grant` 可授予；不 root 也可）。

Settings.Secure 键（`Settings.java` ~L9328-9370，全部 `@hide`）：
| 键 | 类型/范围 | 说明 |
|---|---|---|
| `reduce_bright_colors_activated` | 0/1 | 是否启用 |
| `reduce_bright_colors_level` | int **0..100** | 强度，默认 50；AOSP 资源 `config_reduceBrightColorsStrengthMin=25`、`Max=90`（Settings UI 上限 90，框架侧 clamp 到 100） |
| `reduce_bright_colors_persist_across_reboots` | 0/1 | 重启后是否保留（为 0 时 `ColorDisplayService` 开机自动关闭） |
| `even_dimmer_activated` / `even_dimmer_min_nits` | 0/1 / nits | Android 13+ "Even Dimmer"（压背光下限，非内容矩阵；HyperOS 是否支持 ⚠️） |

实现与强度语义（`ColorDisplayService.java`：监听 Secure 变化 → `ReduceBrightColorsTintController`）：
- `setMatrix(level)` clamp 到 0..100，`computeComponentValue = s²·a + s·b + c`，线性系数 AOSP 默认 `a≈0、b=-0.9556、c=1.0` → **level=90 ≈ 输出 0.14（压暗 ~86%）**，level=100 ≈ 0.044（~95.6%）。
- 关键限制：`isAvailable()` = `ColorDisplayManager.isColorTransformAccelerated(context)`，即需 HWC 支持 color transform、且无 DRM/安全视频时**影响整个显示**（全局，非单应用）。对 HDR 视频/受保护内容可能无效。
- 因此它**只能作为"全局辅助深度压暗"**，不能替代 overlay（无法只暗某区域、会连状态栏一起暗、会影响截图/录屏颜色、用户设置里会看到"极暗"被打开）。

adb/权限命令：
```bash
# 直接调（无需装 app）
adb shell settings put secure reduce_bright_colors_activated 1
adb shell settings put secure reduce_bright_colors_level 100
adb shell settings put secure reduce_bright_colors_persist_across_reboots 1
adb shell settings get secure reduce_bright_colors_level

# 给本应用授权（清单里声明 WRITE_SECURE_SETTINGS 后）
adb shell pm grant <your.package> android.permission.WRITE_SECURE_SETTINGS
# 之后应用内 Settings.Secure.putInt(...) 即可，ColorDisplayService 的 ContentObserver 会立刻应用
```
- Android 14/16：键与 ColorDisplayService 逻辑仍在 main 分支（即 Android 16/17），**未废弃**；但 ⚠️ HyperOS 是否屏蔽/覆盖（部分小米机型有自家"极暗"开关并重置该 Secure 值）需真机验证：写入后 `settings get` 看是否被改回。
- 若不想依赖 WRITE_SECURE_SETTINGS：可用 `adb shell` 脚本方式，或引导用户进"设置 → 无障碍 → 极暗"手动开（但不能调 level）。

来源：
- Settings.java https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/provider/Settings.java
- ColorDisplayService.java / ReduceBrightColorsTintController.java / core/res/res/values/config.xml（同仓库）

---

## 8. 全屏 overlay 能盖住什么（层级表）✅ 源码 + ⚠️ 真机

`WindowManagerPolicy.getWindowLayerFromTypeLw`（main）关键层号（越大越上层）：

| 层 | 窗口类型 | 备注 |
|---|---|---|
| 7 | TYPE_TOAST | |
| 11 | **TYPE_APPLICATION_OVERLAY** | 普通应用遮罩所在 |
| 13 / 14 | IME / IME_DIALOG | 键盘在遮罩之上 |
| 15 | TYPE_STATUS_BAR | 状态栏 |
| 17 | TYPE_NOTIFICATION_SHADE | 下拉通知栏/QS |
| 19 | TYPE_KEYGUARD_DIALOG | 锁屏相关 |
| 22 | TYPE_VOLUME_OVERLAY | 音量条 |
| 24 / 25 | NAVIGATION_BAR / _PANEL | 导航栏 |
| 26 | TYPE_SCREENSHOT | 截屏选择 |
| 30 | TYPE_DRAG | 拖拽层 |
| **31** | **TYPE_ACCESSIBILITY_OVERLAY** | 无障碍遮罩 |
| 33 | TYPE_SECURE_SYSTEM_OVERLAY | 系统安全层 |

逐项结论：
1. **状态栏 / 导航栏**：`TYPE_APPLICATION_OVERLAY`(11) **盖不住**(15/24) → 状态栏/手势条区域不暗。`TYPE_ACCESSIBILITY_OVERLAY`(31) 可以盖住（⚠️ HyperOS 是否额外隐藏 trusted overlay 需实测）。
2. **锁屏 keyguard**：app overlay(11) 在 keyguard(17/19) 之下 → **不显示在锁屏上**（且 app overlay 本就不随 activity 显示）；a11y overlay(31) 层级高于 keyguard，理论可显示（⚠️ 需实测；HyperOS 有「锁屏显示」权限与 keyguard 隐藏逻辑）。
3. **息屏显示 AOD/doze**：doze 阶段只有 doze surface 参与合成，普通窗口（含 overlay）**不显示、也无法覆盖**；且此时屏幕由 doze 亮度控制。（源码层面：Display 进入 DOZE 状态后 WMS 不对普通窗口做合成，⚠️ 各厂商 doze 实现不同，需实测。）
4. **其他系统层**（音量条、截屏、来电、指纹/支付 UI、系统对话框）：均高于 app overlay，盖不住；a11y overlay 高于其中大部分（音量条 22、截屏 26、拖拽 30 都低于 31），但仍低于 SECURE_SYSTEM_OVERLAY(33)/POINTER(35)。
5. **通知栏下拉时**：遮罩仍在（层级未变），只是被通知栏窗口盖住其上部分区域；HyperOS 上可能出现"边缘露光"。

---

## 9. 决策建议（给实现方）

| 方案 | 最大暗度 | 触摸 | 覆盖状态栏 | 用户门槛 | 稳定性 |
|---|---|---|---|---|---|
| A. 普通 overlay，`params.alpha=0.79` | ~79% 内容变暗 | 透传（受 0.8 阈值约束，需留余量） | 否 | 仅"显示在其他应用上层" | 高 |
| B. 普通 overlay + FLAG_DIM_BEHIND + dimAmount 上限 | 可到 ~100%（dim 不参与遮挡） | 透传 | 否（dim 只在窗口层之下） | 同上 | 中（厂商可能 clamp dim） |
| C. 无障碍 overlay `TYPE_ACCESSIBILITY_OVERLAY` | 任意（alpha 0.95+） | 透传（trusted，不受 0.8 限制） | 是（层级 31） | 开无障碍（HyperOS 易被关） | 中低（需守护无障碍） |
| D. 写 `reduce_bright_colors_level` | ~95%（全局） | 不影响触摸 | 是（全局生效） | adb 授 WRITE_SECURE_SETTINGS 或手动开极暗 | 中（全局副作用、厂商可能重置） |

- 建议：**A 做默认**（合规、门槛低）；**B 作为"深度压暗"可选**（transparent 窗 + dim，实测 dim 是否被 clamp）；**C 作为"无障碍深度模式"**（唯一能既压 90%+ 又透传触摸的方案，但必须做无障碍被关后的降级/引导）；**D 作为附加开关**（adb 授权后可调，全局副作用需在 UI 明示）。
- 不要用"多窗口叠 alpha 累乘绕过 0.8"——按 uid 累乘，必然触发拦截。

## 10. 待真机验证清单（按优先级）

1. HyperOS 3 上 `TYPE_APPLICATION_OVERLAY` 的 `params.alpha` 是否被厂商二次处理（0.79 是否真的可透传；跑 logcat 看 `Untrusted touch due to occlusion`）。
2. `FLAG_DIM_BEHIND` 的 `dimAmount=0.9/1.0` 在 HyperOS 上是否被 clamp / 是否只暗 Task 区域。
3. `TYPE_ACCESSIBILITY_OVERLAY` 全屏 NOT_TOUCHABLE 窗口：能否盖状态栏/导航栏、是否出现在锁屏、是否被 HyperOS 隐藏或杀掉无障碍。
4. 锁屏/AOD 下 overlay 是否显示（分别测「锁屏显示」权限开/关）。
5. 写 `reduce_bright_colors_level=100` 后 HyperOS 是否保持、观感与 `even_dimmer` 叠加效果。
6. Android 15+ SAW→FGS 顺序（先 addView 再 startForeground）在 HyperOS 上是否强制。
7. 下拉通知栏/控制中心/多任务界面时遮罩区域的实际视觉表现。

## 附：核心源码/文档链接

- InputDispatcher（阈值 0.8、累乘、frame 判定）：https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/inputflinger/dispatcher/InputDispatcher.cpp
- WindowManager.java（FLAG_NOT_TOUCHABLE javadoc、alpha/dimAmount 文档）：https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/view/WindowManager.java
- WindowState.java（shouldWindowHandleBeTrusted / getTouchOcclusionMode）：https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/wm/WindowState.java
- InputMonitor.java（isTrustedOverlay 类型白名单）：https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/wm/InputMonitor.java
- WindowManagerService.java（a11y token 校验 sanitizeWindowType）：https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/wm/WindowManagerService.java
- WindowManagerPolicy.java（getWindowLayerFromTypeLw 层级表）：https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/policy/WindowManagerPolicy.java
- Dimmer.java（dim 是 SurfaceControl 而非 Window）：https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/wm/Dimmer.java
- AccessibilityService API（createWindowContext(TYPE_ACCESSIBILITY_OVERLAY) 官方示例）：https://developer.android.com/reference/android/accessibilityservice/AccessibilityService
- 前台服务类型（specialUse）：https://developer.android.com/develop/background-work/services/fgs/service-types
- Android 15 行为变更（BOOT_COMPLETED / SAW→FGS / dataSync 超时）：https://developer.android.com/about/versions/15/behavior-changes-15
- Android 16 行为变更：https://developer.android.com/about/versions/16/behavior-changes-16
- Settings.java（reduce_bright_colors_* 键）：https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/provider/Settings.java
- ColorDisplayService.java / ReduceBrightColorsTintController.java：https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/display/color/
- HyperOS 杀无障碍实测/修复：https://github.com/chkndrp/hyperos-accessibility-fix ；https://github.com/haku4130/hyperos-accessibility-guard
- 屏幕滤镜类 App 用户讨论（小米）：https://www.zhihu.com/question/525943762
