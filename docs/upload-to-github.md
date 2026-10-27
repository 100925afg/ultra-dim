# 上传到 GitHub（本地没有外网，请在你自己的网络环境执行）

仓库已经初始化并提交好了：`main` 分支有 `8fd8b00`（1.0）和 `dbdadd3`（1.1）两个提交，附注标签 `v1.0` / `v1.1`。
你只需要在 GitHub 网页上新建仓库，然后把本地仓库推上去。

## 1. 在 GitHub 新建仓库

1. 打开 <https://github.com/new>
2. Repository name 填 **`ultra-dim`**（这个仓库名是功能性命名：超过系统下限的压暗工具，和包名 `com.ultradim`、应用名「极暗 UltraDim」一致）
3. 选 **Public**（要允许别人二次分发就得公开）
4. **不要**勾选 "Add a README file" / "Add .gitignore" / "Choose a license"（本地已经有了，勾了会冲突）
5. 点 Create repository

> 如果你想换个更直白的名字（比如 `screen-dimmer`、`ultra-dim-android`），改完仓库名后把下面命令里的 URL 一起改掉，另外 README/LICENSE/strings.xml 里的 `github.com/100925afg/ultra-dim` 也要一起替换（`strings.xml` 里是 `project_url`、`author_line` 两条）。

## 2. 本地推送

```powershell
cd F:\deepseek-harness\default-workspace\ultra-dim

# 关联远程仓库（SSH 或 HTTPS 任选其一）
git remote add origin https://github.com/100925afg/ultra-dim.git
# 或者：git remote add origin git@github.com:100925afg/ultra-dim.git

# 推送主分支和两个标签
git push -u origin main
git push origin --tags
```

如果 HTTPS 方式要你输入密码，注意 GitHub 现在要求用 **Personal Access Token** 代替密码：
GitHub → Settings → Developer settings → Personal access tokens → Fine-grained tokens → 勾选该仓库的 Contents: Read and write → 生成后把 token 当密码粘贴。

## 3. 推送后在 GitHub 上补的内容（建议）

- 仓库右侧 About 里填 Description：`比系统最低亮度更暗的 Android 压暗工具（Android 14+，含无障碍深度模式）`，Topics 填 `android` `screen-dimmer` `brightness` `accessibility` `overlay`。
- Releases 页面：用标签 `v1.1` 新建一个 release，把 `release\UltraDim-1.1.apk` 上传为附件，正文复制 README 的「版本」一节。
- 确认 LICENSE 被 GitHub 识别为 "Other / 自定义许可"（非 OSI 开源许可，属正常）。

## 4. 每次发新版本

```powershell
cd F:\deepseek-harness\default-workspace\ultra-dim
powershell -NoProfile -ExecutionPolicy Bypass -File tools\build_offline.ps1 -VersionName 1.2 -VersionCode 3
Copy-Item dist\UltraDim-1.2.apk release\UltraDim-1.2.apk
(Get-FileHash release\UltraDim-1.2.apk -Algorithm SHA256).Hash + " *UltraDim-1.2.apk" | Add-Content release\SHA256SUMS.txt
git add -A
git commit -m "极暗 UltraDim 1.2：<改了什么>"
git tag -a v1.2 -m "极暗 UltraDim 1.2"
git push origin main --tags
```
