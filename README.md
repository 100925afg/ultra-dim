# 极暗 · UltraDim

比系统最低亮度更暗的 Android 压暗工具：不改系统亮度，用一层可调不透明度的黑遮罩继续压暗全屏。

[![Release](https://img.shields.io/github/v/release/100925afg/ultra-dim?style=flat-square)](https://github.com/100925afg/ultra-dim/releases)
[![Android](https://img.shields.io/badge/Android-14%2B-3ddc84?style=flat-square)](#)
[![License](https://img.shields.io/badge/license-自定义非商业-lightgrey?style=flat-square)](LICENSE)
[![No Network](https://img.shields.io/badge/不联网-无网络权限-brightgreen?style=flat-square)](#)

<img src="docs/images/main.jpg" width="320" alt="主界面截图">

## 使用场景

为您提供更低的亮度，这下妈妈再也不用担心我半夜偷吃闪光弹了！

## 功能

- **无极调节**：0%–80% 连续可调，不改变系统亮度值，退出即恢复
- **深度模式**：开启无障碍服务后可压到 95%，且触摸、状态栏、输入法一起压暗（普通模式受 Android 12+ 触摸规则限制，上限 80%）
- **悬浮球**：拖动调亮度，双击开关，长按隐藏
- **通知与快捷磁贴**：下拉通知栏一键开关，磁贴支持 5% 步进
- **开机恢复**：重启后自动回到上次状态

## 安装

1. 到 [Releases](https://github.com/100925afg/ultra-dim/releases/latest) 下载 `UltraDim-x.y.apk`
2. 手机上允许「安装未知来源应用」后安装
3. 想自己编译？见 [docs/BUILD.md](docs/BUILD.md)

<details>
<summary>用 adb 安装（可选）</summary>

```bash
adb install -r UltraDim-x.y.apk
```
</details>

## 快速上手

1. 打开「极暗」，在「权限与后台保活」里授予**悬浮窗**（显示在其他应用上层）
2. 授予**通知**权限，让前台服务通知常驻
3. 拖动首页滑块或浮球调到你想要的暗度
4. （可选）需要更暗：打开「深度模式」，按提示到系统「无障碍」里开启「极暗」

## 需要注意

- **不改系统亮度**：遮罩只在显示层压暗，退出 App 立刻恢复，不影响系统设置
- **不省电**：屏幕自身亮度不变，只是显示内容变暗
- **会被录屏/截图录进去**：这是「所见即所得」，录屏软件会录制遮罩层，不是软件 bug
- **普通模式盖不住系统状态栏**：系统状态栏属于更高层；深度模式可以盖住
- **HyperOS 额外注意!!!**：「自启动」开启后软件自动保活，会常驻在后台无法杀掉。若感觉影响日常使用请关闭该软件的「自启动」。

## 隐私

**不申请网络权限，不收集任何数据。** 所有设置只存在本机 SharedPreferences 里。

## 许可证

允许自由使用、修改、二次分发；分发或发布衍生作品时须保留项目链接 <https://github.com/100925afg/ultra-dim>（署名可选），且未经作者书面许可不得用于商业用途。详见 [LICENSE](LICENSE)。

## 碎碎念

灵感来源于一些漫画阅读类的软件，一想到软件内都有人做这种功能来提升夜间阅读的舒适度，那我也可以在整个手机上搞个这个啊，所以就诞生了这个项目，仅此而已。

长时间在黑暗环境下阅读对眼睛有害，还请各位多多注意眼部保护，适当用眼注意休息。
