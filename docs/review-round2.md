# 第二轮代码复审记录（深度模式链路）

审查对象：接入「深度模式（无障碍图层）」之后的源码快照
（`DimService.java` 737 行 / `DeepDimService.java` 216 行 / `MainActivity.java` 1067 行 / `State.java` 179 行）。
本轮为**只读审查**，未修改文件；下面是问题清单与处置结果。

## 已修复

| 编号 | 级别 | 问题 | 处置 |
|---|---|---|---|
| A1 | P1 | `DeepDimService.applyAlpha` 在 alpha≈0 时 `removeView()` 并把 `windowManager` 置空 → 0%↔非 0% 来回穿越时反复 `createWindowContext`+`addView`（昂贵操作），且浮球借用层瞬间为 null | `applyAlpha` 的 0 档改为「保留窗口、仅把 alpha 归零」；`windowManager`/`windowContext` 只在 `release()` 清理 |
| A2 / C4 | P1 | 无障碍连接/断开后只有 `requestExternalRefresh()`，不广播 → Activity 停在「⚠ 未连接」，需手动拨两次开关 | 新增 `DimService.notifyStateChanged()`；`DeepDimService` 的 `onServiceConnected`/`release` 末尾调用 |
| B1 | P1 | 深度模式下拖动浮球到 0%（或双击）会在**触摸回调栈内**同步 `removeViewImmediate`+`addView` 重建窗口（一次双击 = 4 次窗口操作 + 1 次 createWindowContext）→ 浮球闪烁、拖动中断、偶发 ANR | 与「隐藏浮球」一致改为 `old.post(...)` 异步重建；重建期间不切换 WindowManager |
| B2 | P1 | 借层条件用 `deepLayerOk`，该值会随图层抖动翻转 → 浮球在两个 WindowManager 间来回迁移 | 改为只看 `state.isDeepActive()` + `overlayWindowManager() != null` |
| E1 | P1 | 「✅ 已生效」只看开关+已连接，不看图层是否真的加上 → `addView` 被 OEM 拒绝时 UI 静默失效 | 新增 `DimService.isDeepLayerOk()`；`MainActivity.deepModeText()` 改为四态（含「图层创建失败，已回退 80%」） |
| E2 | P1 | 打开深度模式开关时先落盘 `deep_mode=true`，用户没去开无障碍 → 永久「未连接」 | 未连接时**不落盘**，只记 `pendingDeep`；`onResume` 700ms 后复查，仍连不上则清标志 + toast + 开关拨回 |
| A3 | P2 | `onConfigurationChanged` 吞异常，relayout 失败后残窗停在旧尺寸 | 记录日志并 `removeView()`，交给下一次 `setDimLevel` 重建 |
| A4 | P2 | `windowContext` 仅局部变量，重建后不释放 | 提升为字段，`release()` 里置空 |
| B3 | P2 | 靠 `useWm != wm` 猜窗口类型，将来 wm 重建会配错类型 → `ADD_BAD_APP_TOKEN` | `showKnob(WindowManager, boolean a11y)` 显式传类型 |
| B4 | P2 | `showKnob` 的 catch 只清字段，若 `addView` 在 attach 后抛异常会泄漏窗口 | catch 里先 `removeViewImmediate` 尽力摘掉 |
| C6 | P2 | `applyWindow` 把 opacity 夹到 80 只写 State 不广播 → 通知栏可能一直显示 95% | 由 `notifyStateChanged()`（A2/C4）覆盖 |
| E4 | P2 | 无障碍配置缺 `isAccessibilityTool` | `res/xml/accessibility_service_config.xml` 加 `android:isAccessibilityTool="true"`（本服务不读屏幕内容） |
| E6 | P3 | 0 档分支无日志，线上难查「为什么 95% 没压暗」 | 加入 `Log.i` / `Log.w`（含 alpha、图层是否存在） |
| D3 | P1 | 图层失败提示没说原因 | toast 改为「深度图层创建失败（请给「极暗」开启悬浮窗权限），已退回普通模式（最高 80%）」 |

## 暂不处理（已评估，收益/风险不成正比）

| 编号 | 级别 | 说明 |
|---|---|---|
| C1 | P2 | 切换深度模式瞬间两个图层 alpha 会有一帧叠加，真机上可能「闪一下」。切模式是低频操作，且做 alpha 动画会引入新的时序问题，暂不做 |
| C5 | P2 | 浮球位置夹取未扣除手势/导航栏 inset，可能停在手势条上（输入法弹出时更明显）。属体验小瑕疵，留待真机反馈 |
| E3 | P2 | `State.K_DEEP_CONNECTED` 已成死写（`isDeepConnected()` 改为活查 `DeepDimService.isConnected()`），保留无害，删字段收益不大 |
| E5 | P3 | `KnobView.MAX=95` 与 `State` 常量重复；长按判据可再收紧，属代码整洁问题 |
| B5 | — | 已核实结论：可触摸的 trusted overlay（72dp 浮球）**不会**阻断其余触摸透传，也不会让下层被判定为「不可信触摸」；唯一副作用是它位于 IME/手势区之上（见 C5） |

## 本轮确认无问题的点

- 打包面：manifest 中 `DeepDimService`（`BIND_ACCESSIBILITY_SERVICE` + a11y intent-filter/meta-data）、`DimService`（`specialUse` + `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`）、dex 中的类、`res/xml/accessibility_service_config.xml` 均已进包；
- `startForeground` 三级降级链路（specialUse → 无 type → 兜底 `setEnabled(false)`+stopSelf）正确，不再有 5s `RemoteServiceException` 路径；
- `FLAG_DIM_BEHIND` / `dimAmount` 三分支互斥、背景色恢复正确，不会残留半透明背景；
- `DeepDimService.release()` 幂等，`onServiceConnected`/`onUnbind`/`onDestroy` 均调用；进程被杀/重连不会出现「UI 显示已连接但没图层」；
- 普通模式（不深度模式）主链路本轮未发现新的 P0/P1。
