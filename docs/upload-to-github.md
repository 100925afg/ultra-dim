# 上传到 GitHub

本地仓库已就绪：`main` 分支有 `8fd8b00`（1.0）、`dbdadd3`（1.1）、`4495cac`（文档）三个提交，附注标签 `v1.0` / `v1.1`。**还没有 remote**，下面两条路选一条执行即可。

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

## 推送后检查

```powershell
git remote -v
git log --oneline --decorate -3     # 应看到 origin/main 指向最新提交，v1.0 / v1.1 两个标签
git ls-remote --tags origin         # 标签是否上去了
```

网页上应看到：3 个提交、2 个 release tag、README 渲染正常、LICENSE 显示为「Other / 自定义许可」（非 OSI 开源许可，属预期）。

## 建议在 GitHub 上补的内容

- 仓库右侧 About → Description：`比系统最低亮度更暗的 Android 压暗工具（Android 14+，含无障碍深度模式）`；Topics：`android` `screen-dimmer` `brightness` `accessibility` `overlay`。
- Releases → Draft a new release → 选标签 `v1.1` → 标题「极暗 UltraDim 1.1」→ 上传附件 `release\UltraDim-1.1.apk` → 正文复制 README 的「版本」一节。
- 如果想跑 GitHub Actions 自动构建，本仓库的离线脚本是 Windows/JDK 依赖，建议改成标准 Gradle 构建再加 workflow（当前没配）。

## 以后发版

```powershell
cd F:\deepseek-harness\default-workspace\ultra-dim
powershell -NoProfile -ExecutionPolicy Bypass -File tools\build_offline.ps1 -VersionName 1.2 -VersionCode 3
Copy-Item dist\UltraDim-1.2.apk release\UltraDim-1.2.apk
$h=(Get-FileHash release\UltraDim-1.2.apk -Algorithm SHA256).Hash
"$h *UltraDim-1.2.apk" | Add-Content release\SHA256SUMS.txt
git add -A
git commit -m "极暗 UltraDim 1.2：<改了什么>"
git tag -a v1.2 -m "极暗 UltraDim 1.2"
git push origin main --tags
```

（记得同时把 `app/build.gradle.kts` 的 `versionCode` / `versionName` 和 README 的版本小节一起改掉。）
