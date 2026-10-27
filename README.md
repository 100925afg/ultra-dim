# 极暗 UltraDim

Android 14+ 的「把屏幕压得更暗」小工具：在你已经把系统亮度调到最低之后，再用一层全屏黑色遮罩把画面继续压暗，并且能在 **0%–80%（普通模式）** 或 **0%–100%（开启深度模式后）** 范围内**无极调节**。

- 纯 Java、零第三方依赖、零 AndroidX，安装包 **约 41 KB**
- 包名 `com.ultradim`，应用名「极暗」，minSdk 34 / targetSdk 35 / compileSdk 35
- 目标机型：REDMI Turbo 4（HyperOS 3.0.304.0 / Android 16），同样适用于其他 Android 14+ 手机

> 说明：本机（开发机）没有连接手机，只做了静态审查和离线构建验证；真机行为请按 [docs/device-test.md](docs/device-test.md) 的清单逐项确认。

---

## 1. 原理（含对原始思路的修正）

你的原始思路——**在最顶层铺一层全屏黑色遮罩，用不透明度压暗**——方向完全正确，本应用沿用它，但补充和修正了几个关键点：

### 1.1 普通模式：`TYPE_APPLICATION_OVERLAY` 全屏黑窗

- 窗口类型：`TYPE_APPLICATION_OVERLAY`（Android 8+ 普通应用唯一可用的悬浮窗类型）；
- `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE`：不抢焦点、不拦触摸；
- `FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_NO_LIMITS` + `layoutInDisplayCutoutMode = ALWAYS` + `setFitInsetsTypes(0)`：不留边、不进 inset、覆盖挖孔区域；
- 压暗靠 `WindowManager.LayoutParams.alpha`（**不是** View 背景色的 ARGB alpha！见下）。

### 1.2 关键的 80% 规则（这是你原始方案最大的坑）

AOSP 源码（`InputDispatcher.cpp`）里有一条硬规则：**非 trusted 的全屏悬浮窗，不透明度 `> 0.8` 时，落在它上面的触摸不再透传给下层应用**（`InputSettings.java` 的默认阈值 `DEFAULT_MAXIMUM_OBSCURING_OPACITY_FOR_TOUCH = 0.8f`，判据是**严格大于**，正好 0.8 允许）。

而且：
- 系统只认 `LayoutParams.alpha`。`alpha=1.0` 的窗口 + 纯黑 `#80000000` 背景 = 系统按 **100% 遮挡**算，触摸全废；
- 同一 UID 的多个遮挡窗口按 `1-(1-a)(1-b)` **累乘**，叠多层遮罩绕不过去（这条已推翻「叠两层 60% = 120% 的暗度」的设想）。

所以本应用：
- **默认开「安全触摸上限 80%」**，可调上限锁 80%，保证「看得见也点得动」；
- 想再深就必须解锁 80%（关安全触摸，或开深度模式），此时**浮球自动出现**作为逃生通道；85%–95% 普通模式下触摸会被拦，只能用浮球/通知/磁贴操作。

### 1.3 深度模式（进阶，可突破 80% 且触摸仍然可用）

无障碍服务（AccessibilityService）添加的 `TYPE_ACCESSIBILITY_OVERLAY` 窗口在系统里属于 **trusted overlay**：`WindowState.shouldWindowHandleBeTrusted()` → `InputMonitor.isTrustedOverlay(type)` 白名单命中，`InputDispatcher::canBeObscuredBy()` 对 trusted overlay 直接返回 `false` —— **它不参与遮挡计算，alpha 可以任意大，触摸照常透传**。

而且它的窗口层级（31）高于状态栏（15）、控制中心（17）、导航栏（24）、输入法（13），所以能真正铺满全屏，连状态栏一起压暗。

因此深度模式 = 关掉普通遮罩的压暗（`alpha=0`），把全部压暗交给无障碍图层承担，用户得到的是**均匀、可到 100%、触摸可用**的压暗。前提是你在系统设置里手动开启「极暗」的无障碍服务（打开开关时会自动跳转过去）。

代价/风险：
- HyperOS 对无障碍服务管得严（有倒计时确认弹窗，也可能被省电策略静默关闭）；
- 无障碍服务被杀后，本应用会自动退回普通模式（上限 80%），不会黑屏；
- 若图层实际加不上（`addView` 失败），同样自动退回普通遮罩并提示，不会出现「UI 说已生效、屏幕却没压暗」。

### 1.4 为什么不用系统 API？

- `Settings.System.SCREEN_BRIGHTNESS` / `WindowManager.LayoutParams.screenBrightness`：只能在系统允许的亮度区间内调，**没法低于硬件/系统下限**；
- 系统「极暗 / Extra dim」（Android 12+）：效果接近，但要它自己的开关或 `WRITE_SECURE_SETTINGS`（adb 授权），强度不能由第三方细调；
- 所以「悬浮窗遮罩」是普通应用达到「比系统最低亮度更暗」的唯一可靠方案，深度模式则是其中唯一能做到「>80% 且触摸可用」的路径。

### 1.5 附带说明

- 遮罩**不省电**：降的是像素亮度，背光没变；OLED 上深色像素略省电，但整体耗电与亮度基本无关。想省电用深色模式，不是这个应用。
- 普通模式下**状态栏/导航栏/输入法**由系统绘制在悬浮窗之上，压不暗（这是 AOSP 的层级决定的）；深度模式可以覆盖。

---

## 2. 安装

APK 在 `dist\UltraDim-1.0.apk`（已用项目内 `keystore\ultradim.jks` 签名，可 `apksigner verify`）。

**手机安装**：把 APK 传到手机（微信文件传输 / 数据线 / 网盘均可）→ 用文件管理器点开 → HyperOS 会提示「未知来源」，选择「允许来自此来源的应用」→ 安装。首次安装如遇「已安装的软件包存在问题」提示，先卸载旧的再装。

**ADB 安装**（推荐，方便看日志）：

```powershell
cd F:\deepseek-harness\default-workspace\ultra-dim
.sdk\platform-tools\adb.exe install -r dist\UltraDim-1.0.apk
```

---

## 3. 首次使用（按顺序做，缺一不可）

| 步骤 | 操作 | 为什么 |
|---|---|---|
| 1 | 打开「极暗」→ 权限卡片里点「悬浮窗」→ 允许「显示在其他应用上层」 | 没有这个权限遮罩根本加不上去 |
| 2 | 点「通知」→ 允许通知 | Android 13+ 前台服务的常驻通知需要运行时权限；拒绝的话服务仍能跑但通知不显示 |
| 3 | 点「电池优化」→ 允许忽略电池优化 | HyperOS 省电策略可能杀进程 |
| 4 | 点「自启动」→ 打开「自启动」和「省电策略：无限制」 | 红米必做，否则息屏/切后台后可能被杀 |
| 5 | 打开「启用遮罩」开关 | 遮罩出现，试试拖动主滑条 |
| 6 | （可选）下拉控制中心 → 编辑 → 添加「极暗」磁贴 | 之后点磁贴一键开关 |
| 7 | （可选）打开「浮球调节」 | 屏幕任意位置拖动上下调暗度；双击开关；长按隐藏 |
| 8 | （进阶）打开「深度模式（进阶）」→ 在系统「无障碍」列表里开启「极暗」 | 解锁 80% 以上且触摸仍可用；会出现 HyperOS 的风险提示，属正常 |

**调暗方式**：
- 主滑条 / `-1% +1%` / 预设 `20% 40% 60% 80%` —— 实时生效；
- 通知栏三个按钮：暗一点 / 亮一点 / 关闭（服务常驻通知）；
- 控制中心磁贴：一键开关；
- 浮球：上拖变暗、下拖变亮、双击切换、长按隐藏。

**上限选择**（设置卡片）：`65% / 80% / 85% / 95%`。安全触摸打开时实际最多 80%；深度模式生效时按所选上限放开。

---

## 4. 安全机制（重要）

1. **不会全黑**：滑条上限受「上限」设置约束，默认 85%（安全模式 80%），不会出现 100% 黑屏（深度模式下即使设 95%，仍有 5% 可见度）。
2. **浮球逃生**：普通模式不透明度 > 80% 时浮球自动出现（即使关掉「浮球调节」开关也会强制显示）；浮球窗口在遮罩之上且可触摸，拖动即可调回来。深度模式下浮球会被放到无障碍图层之上，不会被压暗到看不见。
3. **通知逃生**：通知栏常驻「关闭」按钮。
4. **磁贴逃生**：控制中心磁贴点一下就关。
5. **关闭安全上限有二次确认**：明确提示「可能导致屏幕完全无法操作」。
6. **深度模式自动降级**：无障碍服务断开或图层添加失败 → 自动退回普通模式（≤80%）并提示，不会把用户锁死在黑屏里。

> 万一真的把屏幕弄到点不动：**下拉通知栏 → 点「关闭」**，或用**浮球**、**控制中心磁贴**、**锁屏再解锁**（部分机型会移除触摸拦截），最后手段是长按电源键重启手机。

---

## 5. 已知限制

- **不能省电**（只降像素亮度，不降背光）。
- **普通模式盖不住系统栏**：状态栏、导航栏、输入法在悬浮窗之上，压不暗；需要全屏均匀压暗请用深度模式。
- **锁屏 / 息屏显示（AOD）**：普通悬浮窗不出现在锁屏和 AOD 上；深度模式的无障碍图层理论上可上锁屏（真机待验证）。
- **录屏 / 截图**：遮罩是普通窗口，会被录屏和截图一起记录下来（「所见即所得」，不是 bug）。
- **系统「隐私保护/敏感页面」**：部分系统页面（如支付、权限弹窗）可能限制悬浮窗显示。
- **HyperOS 后台**：未做自启动/电池白名单时，长时间后台可能被回收；开启前述设置后通常能常驻。无障碍服务也可能被系统静默关闭，届时自动降级。
- **>80% 触摸拦截（普通模式）**：安全模式默认规避；解锁后靠浮球/通知救回，或改用深度模式。
- 深色遮罩在个别机型上可能与「护眼模式/色温」相互影响，表现为颜色偏暖/偏冷，属正常。

---

## 6. 从源码构建

### 方式 A：离线脚本（本仓库自带工具链，无需 Gradle / 无需联网）

环境要求：Windows + JDK 17+（`javac`/`keytool` 在 PATH）+ Python 3。

```powershell
cd F:\deepseek-harness\default-workspace\ultra-dim

# 第一次：下载最小 Android SDK（build-tools 35.0.1 + platform 35 + platform-tools，约 130 MB）
powershell -NoProfile -ExecutionPolicy Bypass -File tools\fetch_sdk.ps1

# 构建（产物 dist\UltraDim-1.0.apk，自动 zipalign + 签名 + 验签）
powershell -NoProfile -ExecutionPolicy Bypass -File tools\build_offline.ps1

# 升版本号
powershell -NoProfile -ExecutionPolicy Bypass -File tools\build_offline.ps1 -VersionName 1.1 -VersionCode 2
```

构建流程：`aapt2 compile` → `aapt2 link`（生成 R.java）→ `javac --release 11` → `d8`（classes.dex）→ `add_dex.py` 把 dex 封进 APK（并保证 `resources.arsc` 不压缩）→ `zipalign -p 4` → `apksigner sign` → `apksigner verify`。

> 签名密钥：`keystore\ultradim.jks`（alias `ultradim`，store/key 密码均为 `ultradim123`）。**升级安装必须用同一份 keystore**，否则会变成「包名冲突、需先卸载」；这份 keystore 只适合自用，不要用在要上架的应用上。

### 方式 B：Android Studio（以后想改 UI 时）

仓库里带了一套标准 Gradle 工程（AGP 8.7.3 / Gradle 8.9 / compileSdk 35 / minSdk 34）。用 Android Studio 打开项目根目录即可。

两个注意点：
1. **本仓库没有包含 `gradle-wrapper.jar`**（离线环境下载不到），首次在 AS 里打开时如果提示 wrapper 缺失，让 AS 用本机 Gradle 或点「Generate Gradle wrapper」重新生成即可；
2. 首次同步需要联网下载 AGP / Gradle 依赖，如果网络不通就继续用方式 A。

---

## 7. 目录结构

```
ultra-dim\
├─ app\src\main\
│  ├─ AndroidManifest.xml
│  ├─ java\com\ultradim\
│  │  ├─ MainActivity.java      主界面（纯代码构建 UI，约 1050 行）
│  │  ├─ DimService.java        前台服务：遮罩窗口 + 浮球窗口 + 通知 + 磁贴动作
│  │  ├─ DeepDimService.java    深度模式：无障碍服务 + TYPE_ACCESSIBILITY_OVERLAY 压暗层
│  │  ├─ KnobView.java          浮球自绘控件（拖动/双击/长按）
│  │  ├─ DimTileService.java    控制中心磁贴
│  │  ├─ BootReceiver.java      开机/更新后自动恢复
│  │  └─ State.java             SharedPreferences 状态与夹取逻辑
│  └─ res\
│     ├─ values\                主题、颜色、字符串
│     ├─ drawable\              磁贴图标、启动图标前景
│     ├─ mipmap-anydpi-v26\     自适应图标（含 monochrome 主题图标）
│     └─ xml\accessibility_service_config.xml
├─ app\proguard-rules.pro       混淆规则（release 默认不混淆，仅保证文件存在）
├─ tools\
│  ├─ build_offline.ps1         离线构建脚本
│  ├─ fetch_sdk.ps1             下载最小 SDK
│  └─ add_dex.py                把 classes.dex 封进 APK
├─ keystore\ultradim.jks        自用签名密钥
├─ dist\UltraDim-1.0.apk        构建产物
├─ docs\device-test.md          真机测试清单
├─ docs\research-platform.md    Android 平台机制调研（遮挡/触摸/FGS/HyperOS）
└─ docs\review-round2.md        代码复审记录（深度模式链路）
```

---

## 8. 调试

```powershell
# 实时日志（崩溃看 AndroidRuntime，应用日志看 UltraDim）
.sdk\platform-tools\adb.exe logcat -s UltraDim:V AndroidRuntime:E

# 遮罩/浮球窗口是否存在
.sdk\platform-tools\adb.exe shell dumpsys window windows | Select-String -Pattern "UltraDim"

# 深度模式图层是否存在（应看到 type=ACCESSIBILITY_OVERLAY）
.sdk\platform-tools\adb.exe shell dumpsys window windows | Select-String -Pattern "UltraDimDeepLayer"

# 服务是否在跑 + 前台服务类型
.sdk\platform-tools\adb.exe shell dumpsys activity services com.ultradim

# 系统的「触摸遮挡不透明度阈值」（不同 ROM 可能为 0.8 或 1.0）
.sdk\platform-tools\adb.exe shell settings get global maximum_obscuring_opacity_for_touch

# 无障碍服务是否被系统关掉
.sdk\platform-tools\adb.exe shell settings get secure enabled_accessibility_services
```

---

## 9. 版本

- **1.0**（2026-10-27）：首个版本（含第二轮代码复审的全部 P1 修复）。遮罩压暗、无极调节、浮球、通知快捷操作、控制中心磁贴、开机恢复、安全触摸上限、实验 DIM_BEHIND 模式、**深度模式（无障碍图层，突破 80% 且触摸可用）**。
