# 从源码构建

本项目是纯 Java + 零第三方依赖（没有 AndroidX / Kotlin），所以既可以走标准 Gradle，也可以用仓库自带的离线脚本在不装 Android Studio 的情况下直接产出已签名的 APK。

## 环境要求

| 方式 | 需要 |
|---|---|
| A. 离线脚本 | Windows + JDK 17+（`javac`/`keytool` 在 PATH）+ Python 3 + 能访问 `dl.google.com` 的网络（仅首次） |
| B. Android Studio | Android Studio（建议 Ladybug 以上）+ JDK 17，首次同步需要能下载 AGP/Gradle |

最低运行版本：Android 14（API 34），编译目标 API 35。

## 方式 A：离线脚本（无需 Gradle）

```powershell
cd ultra-dim

# 第一次：下载最小 Android SDK（build-tools 35.0.1 + platform 35 + platform-tools，约 130 MB）
powershell -NoProfile -ExecutionPolicy Bypass -File tools\fetch_sdk.ps1

# 构建（产物 dist\UltraDim-<版本号>.apk，自动 zipalign + 签名 + 验签）
powershell -NoProfile -ExecutionPolicy Bypass -File tools\build_offline.ps1

# 指定版本号
powershell -NoProfile -ExecutionPolicy Bypass -File tools\build_offline.ps1 -VersionName 1.1 -VersionCode 2
```

构建链路：`aapt2 compile` → `aapt2 link`（生成 R.java）→ `javac --release 11` → `d8`（classes.dex）→ `tools\add_dex.py` 把 dex 封进 APK（并保证 `resources.arsc` 保持 STORED 不压缩）→ `zipalign -p 4` → `apksigner sign` → `apksigner verify`。

**签名密钥**：脚本首次运行会在 `keystore\` 下自动生成 `ultradim.jks`，口令**随机生成**并写入 `keystore\keystore.properties`（两者都在 `.gitignore` 里，不要提交）。也可以用环境变量 `ULTRADIM_KS_PASS` 覆盖。Android 要求升级安装必须使用同一份签名，请自行备份这个目录；正式分发建议生成你自己的密钥。

## 方式 B：Android Studio

仓库带有标准 Gradle 工程（AGP 8.7.3 / Gradle 8.9 / compileSdk 35 / minSdk 34 / Java 11），直接用 Android Studio 打开项目根目录即可。

两个注意点：

1. **仓库不包含 `gradle-wrapper.jar`**（离线环境无法生成二进制），首次打开若提示 wrapper 缺失，让 AS 用本机 Gradle，或点 “Generate Gradle wrapper” 重新生成；
2. 首次同步需要联网下载 AGP / Gradle 依赖；网络不通时请用方式 A。

## 目录结构

```text
ultra-dim/
├─ app/src/main/
│  ├─ AndroidManifest.xml
│  ├─ java/com/ultradim/
│  │  ├─ MainActivity.java      主界面（纯代码构建 UI）
│  │  ├─ DimService.java        前台服务：遮罩窗口 + 浮球窗口 + 通知 + 磁贴动作
│  │  ├─ DeepDimService.java    深度模式：无障碍服务 + TYPE_ACCESSIBILITY_OVERLAY 压暗层
│  │  ├─ KnobView.java          浮球自绘控件（拖动 / 双击 / 长按）
│  │  ├─ DimTileService.java    控制中心磁贴
│  │  ├─ BootReceiver.java      开机 / 应用更新后自动恢复
│  │  └─ State.java             SharedPreferences 状态与夹取逻辑
│  └─ res/
│     ├─ values/                主题、颜色、字符串
│     ├─ drawable/              磁贴图标、启动图标前景
│     ├─ mipmap-anydpi-v26/     自适应图标（含 monochrome 主题图标）
│     └─ xml/accessibility_service_config.xml
├─ app/proguard-rules.pro       混淆规则（release 默认不混淆）
├─ tools/
│  ├─ build_offline.ps1         离线构建脚本
│  ├─ fetch_sdk.ps1             下载最小 SDK
│  ├─ add_dex.py                把 classes.dex 封进 APK
│  └─ gh_relay.js               git TLS 不可用时的 GitHub 转发中继（Node）
├─ release/                     发布副本 + SHA256SUMS.txt
├─ docs/                        平台调研、测试清单、发版说明
└─ LICENSE
```

## 调试

```powershell
# 实时日志（崩溃看 AndroidRuntime，应用日志看 UltraDim）
.sdk\platform-tools\adb.exe logcat -s UltraDim:V AndroidRuntime:E

# 遮罩 / 浮球窗口是否存在
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

## 原理与平台机制

遮罩为什么在 80% 处遇到触摸限制、深度模式为什么能绕过它（trusted overlay）、各 ROM 的差异，见 [research-platform.md](research-platform.md)。
