# 极暗 UltraDim v1.2

> ⚠️ **签名密钥已更换：从 v1.1（或更早）升级必须先卸载旧版**，否则会提示「应用未安装」。卸载会清空 App 内设置（不透明度、浮球位置等），从 v1.2 起签名固定，之后的版本可正常覆盖升级。
>
> 更换原因：旧版构建脚本里的密钥口令曾意外出现在公开仓库中。密钥文件本身从未上传，这是按最坏情况做的预防措施。

## 这个版本改了什么

- App 内「关于」页新增**免责声明**与**非官方声明**（与 README 一致，随 APK 一起分发）
- README 顶部新增醒目免责声明；新增「校验下载的 APK」说明；修复截图不显示、补齐 `docs/BUILD.md`、截图体积 3.0 MB → 274 KB
- 构建脚本不再内置密钥口令（改为随机的 `keystore/keystore.properties` 或环境变量 `ULTRADIM_KS_PASS`）

功能与 v1.1 相同（遮罩、深度模式、浮球、通知/磁贴、开机恢复）。

## 校验

```powershell
Get-FileHash .\UltraDim-1.2.apk -Algorithm SHA256
apksigner verify --print-certs .\UltraDim-1.2.apk
```

- SHA-256：`F968B735FD3E6DE0361F557AAE0F5CD913B4FE5A257A530D630B7D2D1A49E667`
- 签名证书 SHA-256：`2cda466a08df81b7f08cd4d0be4c506e82e590a49cb39768a7a130095510ef58`
