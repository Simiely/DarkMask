# CHANGELOG.md · 版本记录

> 项目无正式版本 tag，按开发迭代记录。完整问题记录见 [DEVELOPMENT.md](DEVELOPMENT.md)。

## 文档维护 · 2026-11-17

- 接入 knowledge-base 的**单项目规范**（spec 2.1.3 / repo-kit 2.7.2，profile `link-only`）
- 新增 `schema/taxonomy.json`、`tools/repo-kit/`、`tools/update-repo-kit.ps1`、`schema/kit.lock.json` 与 CI
- 只查链接与入口，**不要求 frontmatter**
- 5 个相对链接纳入检查
- 本仓 `Build DarkMask APK` 既有失败（sdkmanager 环境问题），与本次接入无关
- 验收：`REPO CHECK: PASS`（ERROR=0）、`KIT LOCK: PASS (v2.7.2, spec=2.1.3)`

## 最新迭代（12 轮问题修复记录）

- **通知修复**（13-14 轮）：渠道重要性无法升高 → 换新渠道 id `darkmask_fg_v2` + IMPORTANCE_HIGH；权限异步时序 → `registerForActivityResult` 回调中启动服务
- **蒙版覆盖修复**：物理全屏尺寸 + `LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS`，覆盖状态栏/刘海
- **签名安全**：明文 keystore 移除 → 7-Zip AES-256 加密 zip + GitHub Secret + git filter-branch 清历史
- **APK 体积优化**：5.9MB → 2.4MB（resConfigs zh + R8 + WebP）
- **FAB/面板稳定性**：动画取消竞争修复、PanelLauncher 广播替代猜时间、isRunning 静态变量
- **HSL 调色体验**：拖色相时 S/L 自动提升、预设对分预览、双击归零不丢预设

## 初始版本

- 全屏降亮护眼工具：全屏蒙版（自定义颜色 × 透明度 5-95%）+ 悬浮按钮（拖动/靠边吸附）+ 3 预设 + HSL 调色 + 通知栏快捷 + 下拉磁贴

## 备注

- 无版本 tag / Release 号；CI 每次 push main 自动构建 debug APK（约 2.4MB）
