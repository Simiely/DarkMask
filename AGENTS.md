# AGENTS.md · 项目规则

> 📌 **文档基线**：2026-11-17（commit `22b3990`）接入单项目规范机器层（link-only）
> 给 AI / 未来的你：只记代码里看不出的关键信息。详细问题记录见 [DEVELOPMENT.md](DEVELOPMENT.md)（12 条一坑一篇）。

## 技术栈

- Kotlin 1.9.24 · AGP 8.5.2 · compileSdk 35 · minSdk 23 · Gradle 8.9
- 纯前台服务（OverlayService）实现，无后台进程；全屏蒙版用 `TYPE_APPLICATION_OVERLAY` + WindowManager
- CI：GitHub Actions push main 自动构建 assembleDebug；固定 debug 签名（keystore AES-256 加密 zip + Secret 密码）

## 关键坑

1. **前台服务通知不显示（Android 13+）**：① 渠道重要性一旦创建无法升高——必须换新渠道 id（如 `darkmask_fg_v2`）+ `IMPORTANCE_HIGH`；② 权限弹窗是异步的——`requestPermissions` 后不能立刻 `startForegroundService`（通知被静默丢弃），用 `registerForActivityResult` 在授权回调中启动
2. **全屏蒙版盖不住状态栏/刘海**：`TYPE_APPLICATION_OVERLAY` + `MATCH_PARENT` 被约束到内容区；用物理全屏尺寸（R+ `maximumWindowMetrics`，非 `getCurrentWindowMetrics`）+ `FLAG_LAYOUT_NO_LIMITS` + `LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS`，取最大边做正方形
3. **签名密钥安全**：明文 keystore 入库 = 高危；用 7-Zip AES-256 加密 zip + GitHub Secret 存密码 + CI 解密；密码与 zip 同仓库形同虚设
4. **APK 体积**：`resConfigs("zh")`（-1MB）+ R8 minify/shrinkResources（-2MB）+ PNG→WebP（-80KB）
5. **Kotlin max/min**：无三参版本，`maxOf` 在 1.9.24 不可用——用 `max(max(a,b),c)` 最稳（测试环境能编过 ≠ CI 能过）
6. **FAB 动画竞争**：ValueAnimator 每次新建不取消旧的会位置不确定——保存引用，开始新动画前 `cancel()` 旧的
7. **服务启动时序**：`startForegroundService` 异步排入主线程队列，广播投递时 receiver 已就绪——用 `ACTION_START_AND_PANEL` 广播替代 `Handler.postDelayed` 猜时间
8. **`getRunningServices()` 已废弃**（Android 5.0+，MIUI/HyperOS 不准）——用 `@JvmStatic var isRunning` 静态变量

## 约定

- 常驻通知：setOngoing + setOnlyAlertOnce + PRIORITY_HIGH + 渠道静音（无声音/振动/角标）
- 预设颜色用 String 存 SharedPreferences（Int 默认值 0 与黑色 -16777216 混淆）
- 构建：`./gradlew assembleDebug`

## 常用命令

- 本地构建：`./gradlew assembleDebug`（产物 `app/build/outputs/apk/debug/`）
- 代理环境：git 配 `http.proxy http://127.0.0.1:7890` + `http.sslVerify false`；artifact 下载走 Python 显式 no-proxy（blob.core.windows.net 代理返回 0 字节）
