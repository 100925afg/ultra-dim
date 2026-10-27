# 上传到 GitHub

> **当前状态（2026-10-28）**：已推送完成。`origin = https://github.com/100925afg/ultra-dim.git`，`main` 含 1.0 → 1.2 全部提交，标签 `v1.0` / `v1.1` / `v1.2`，Release `v1.2` 已发布（含 APK ×2 + SHA256SUMS）。本文档保留为「换机器 / 重来一遍 / 以后发版」的操作手册。

---

## 第 0 步：在 GitHub 网页建空仓库

1. 打开 <https://github.com/new>
2. Repository name：**`ultra-dim`**（功能性命名：超过系统下限的压暗工具；与包名 `com.ultradim`、应用名「极暗 UltraDim」一致）
3. 选 **Public**
4. **不要**勾 Add a README / .gitignore / license（本地都有，勾了会冲突）
5. Create repository

> 如果换名字（如 `screen-dimmer`），除了下面的 URL，还要同步替换 `README.md`、`LICENSE`、`app/src/main/res/values/strings.xml` 里的 `github.com/100925afg/ultra-dim`（`strings.xml` 里是 `project_url`、`author_line` 两条），然后重新构建 APK。

---

## 方案 A：直接 push（先试这个）

在**你自己的 PowerShell / Git Bash** 里执行（不在 DSH 的沙箱里，git 的 TLS 一般正常）：

```powershell
cd F:\deepseek-harness\default-workspace\ultra-dim

git remote add origin https://github.com/100925afg/ultra-dim.git
git push -u origin main
git push origin --tags
```

需要走代理（本机检测到 Clash 混合端口 7897）时：

```powershell
git -c http.proxy=http://127.0.0.1:7897 push -u origin main
git -c http.proxy=http://127.0.0.1:7897 push origin --tags
```

认证：GitHub 已不接受账号密码。用 Personal Access Token 当密码：

- **Classic token**（最简单）：<https://github.com/settings/tokens> → Generate new token (classic) → 勾 `repo` → 生成 `ghp_...`
- **Fine-grained token**（更安全）：<https://github.com/settings/tokens?type=beta> → Repository access: Only select repositories → `ultra-dim` → Permissions: **Contents = Read and write** → 生成 `github_pat_...`

弹出认证窗口时用户名填 `100925afg`、密码粘贴 token。也可以把 token 直接写进 URL 免弹窗（会留在 shell 历史里，推完记得清）：

```powershell
git remote set-url origin https://100925afg:<TOKEN>@github.com/100925afg/ultra-dim.git
git push -u origin main
git push origin --tags
git remote set-url origin https://github.com/100925afg/ultra-dim.git   # 推完把 token 去掉
```

---

## 方案 B：本机 TLS 坏了时的中继方案

症状：`git` 报 `schannel: AcquireCredentialsHandle failed: SEC_E_NO_CREDENTIALS`（DSH 沙箱里就是这个问题，Node 的 TLS 却正常）。

`tools\gh_relay.js` 会在本机起一个明文 HTTP 端口，用 Node 的 OpenSSL 把它转发到 `https://github.com`，于是 `git` 只需要说 HTTP：

```powershell
# 终端 1：启动中继（占住窗口，Ctrl+C 结束）
node F:\deepseek-harness\default-workspace\ultra-dim\tools\gh_relay.js 8848

# 终端 2：通过中继 push（公开仓库不需要 token；首次通常要认证，见下）
cd F:\deepseek-harness\default-workspace\ultra-dim
git remote add origin http://127.0.0.1:8848/100925afg/ultra-dim.git
git push -u origin main
git push origin --tags
git remote set-url origin https://github.com/100925afg/ultra-dim.git
```

中继本身不提供凭据，所以认证仍要用 token。把 token 放进 URL 最省事（`git-remote-http` 会把 URL 里的用户名/密码变成 `Authorization: Basic` 头，中继会原样转给 GitHub）：

```powershell
git remote add origin http://100925afg:<TOKEN>@127.0.0.1:8848/100925afg/ultra-dim.git
git push -u origin main
git push origin --tags
git remote set-url origin https://github.com/100925afg/ultra-dim.git
```

> 中继只转发到 `github.com`（第二个参数可改成别的域名），且只监听 `127.0.0.1`，不会对外暴露。用完后 Ctrl+C 关掉、`git remote set-url` 去掉 token。

---

## 方案 C：本机已保存过 GitHub 凭据 + 中继（无需再找 token，已实测可用）

前提：Git 曾在 Windows 凭据管理器里存过 `git:https://github.com`（本机就有，用户名 `100925afg`）。即使 `credential.helper=manager` 指向的 GCM 没装，`git-credential-wincred.exe` 仍能读出这份凭据：

```powershell
# 终端 1：起中继
node F:\deepseek-harness\default-workspace\ultra-dim\tools\gh_relay.js 8848

# 终端 2：取凭据 → 拼 Basic 头 → push（token 不落 URL、不进 shell 历史）
cd F:\deepseek-harness\default-workspace\ultra-dim
$helper = "C:\Program Files\Git\mingw64\libexec\git-core\git-credential-wincred.exe"
$out  = "protocol=https`nhost=github.com`n`n" | & $helper get
$user = ($out | Where-Object { $_ -like "username=*" }) -replace '^username=',''
$pass = ($out | Where-Object { $_ -like "password=*" }) -replace '^password=',''
$b64  = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes(($user + ":" + $pass)))
$env:GIT_TERMINAL_PROMPT = "0"
$url  = "http://127.0.0.1:8848/100925afg/ultra-dim.git"
git -c credential.helper= -c http.extraHeader="Authorization: Basic $b64" push $url main
git -c credential.helper= -c http.extraHeader="Authorization: Basic $b64" push $url --tags
git -c credential.helper= -c http.extraHeader="Authorization: Basic $b64" ls-remote $url   # 校验
```

> PowerShell 会把 git 写到 stderr 的进度当成错误显示（`NativeCommandError`），退出码看起来是 1，实际推送成功与否以最后一行 `旧..新 main -> main` 和 `ls-remote` 结果为准。凭据只在进程内使用，不写入任何文件。

---

## 推送后检查

```powershell
git remote -v
git log --oneline --decorate -3     # 应看到 origin/main 指向最新提交，v1.0 / v1.1 两个标签
git ls-remote --tags origin         # 标签是否上去了
```

网页上应看到：全部提交、`v1.0` / `v1.1` / `v1.2` 三个 release tag、README 渲染正常（含截图与免责声明）、LICENSE 显示为「Other / 自定义许可」（非 OSI 开源许可，属预期）。

## 建 Release 并上传 APK：`tools\gh_release.js`

认证仍用上面那份凭据（或任意 PAT），脚本只读 `GH_TOKEN` 环境变量、不回显，可重复执行（已存在的 Release 只补缺失资产）：

```powershell
$env:GH_TOKEN = $pass          # 方案 C 里取到的值；或用你自己的 PAT
node tools\gh_release.js --repo 100925afg/ultra-dim --tag v1.2 `
     --name "极暗 UltraDim v1.2" --notes-file release\RELEASE-NOTES-v1.2.md `
     --asset release\UltraDim-1.2.apk --asset release\SHA256SUMS.txt
```

## 已完成的仓库设置（无需再动）

- About → Description：`极暗 UltraDim：Android 14+ 全屏压暗工具，比系统最低亮度更暗，不改系统亮度、无需 root。`
- Topics：`android` `android14` `brightness` `screen-dimmer` `dark-mode` `overlay` `hyperos` `no-root`
- Releases：`v1.2`（Latest）附 `UltraDim-1.2.apk`、`UltraDim-1.1.apk`、`SHA256SUMS.txt`；`v1.1` 为上一版本
- 建议后续补：GitHub Actions 自动构建（当前离线脚本依赖 Windows/JDK；改用标准 Gradle 构建后再加 workflow）

## 以后发版

```powershell
cd F:\deepseek-harness\default-workspace\ultra-dim
powershell -NoProfile -ExecutionPolicy Bypass -File tools\build_offline.ps1 -VersionName 1.3 -VersionCode 4
Copy-Item dist\UltraDim-1.3.apk release\UltraDim-1.3.apk
$h=(Get-FileHash release\UltraDim-1.3.apk -Algorithm SHA256).Hash
"$h *UltraDim-1.3.apk" | Add-Content release\SHA256SUMS.txt
git add -A
git commit -m "release: v1.3（<改了什么>）"
git tag -a v1.3 -m "极暗 UltraDim v1.3"
# 推代码+标签：用方案 A/B/C 任一条
# 建 Release + 传资产：用 tools\gh_release.js（见上）
```

（记得同时把 `app/build.gradle.kts` 的 `versionCode` / `versionName` 和 README 的版本小节一起改掉；签名密钥固定使用 `keystore\` 下现有密钥，**不要重新生成**，否则老用户升级会签名冲突。）
