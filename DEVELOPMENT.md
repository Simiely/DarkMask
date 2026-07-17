# 夜深模式 — 开发笔记

## 架构概览

```
MainActivity          — 主界面（权限引导 + 完整设置）
  ├─ OverlayService   — 前台服务（常驻，管理蒙版/FAB/控制面板）
  │   ├─ dimView      — 全屏蒙版（物理尺寸，覆盖状态栏/刘海）
  │   ├─ fab          — 悬浮按钮（可拖动、靠边吸附）
  │   └─ panel        — 悬浮控制面板（透明度/颜色预设/HSL）
  ├─ PanelLauncher    — 透明中转 Activity（通知点击→弹面板）
  └─ MaskTileService  — 下拉快捷磁贴
Prefs                  — SharedPreferences 封装
ColorUtil              — HSL ↔ RGB 转换
```

## CI/CD

- **GitHub Actions** — push 到 `main` 自动触发 `assembleDebug`
- **JDK 17 + Android SDK 35 + Gradle 8.9** — 仓库无 wrapper，CI 首跑生成
- **固定 debug 签名** — `keystore/debug-keystore.zip`（AES-256 加密），密码存在 GitHub Secret `KEYSTORE_ZIP_PASSWORD`，CI 用 7z 解密
- **产物** — APK 上传为 `DarkMask-debug` artifact，约 2.4MB

### 本地构建环境（代理网络）

作者在带 MITM 代理（127.0.0.1:7890）的 Windows 上构建。关键配置：

```bash
git config --global http.proxy http://127.0.0.1:7890/
git config --global https.proxy http://127.0.0.1:7890/
git config --global http.sslVerify false
# curl 需要 -k 跳过证书校验
```

### 产物下载（代理绕过 Azure blob）

GitHub Actions 的 artifact 下载 URL 指向 `*.blob.core.windows.net`，代理返回 HTTP 200 但 body 为 0 字节。**绕不过 curl——但 Python 显式 no-proxy 可直连下载**：

```python
import urllib.request
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
# 注意：blob 的 SAS 签名 URL 自带鉴权，绝不能带 Authorization 头
class StripAuth(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, hdrs, newurl):
        return urllib.request.Request(newurl, headers={})
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), StripAuth)
```

---

## 关键问题记录

### 1. 前台服务通知不显示

**表象**：Android 13+，蒙版运行正常，但通知栏看不到常驻通知。

**根因**（两个问题叠加）：

1. **渠道重要性不够**（第 13 轮）：旧渠道 `IMPORTANCE_LOW`，HyperOS 自动折叠/隐藏。且**已创建的渠道重要性无法在代码内升高**——必须换新渠道 id 才能让 `IMPORTANCE_HIGH` 生效。
   ```kotlin
   // 必须用新 id，旧渠道一旦创建重要性就固定了
   val chId = "darkmask_fg_v2"  // 不是 "darkmask_channel"
   val ch = NotificationChannel(chId, "...", NotificationManager.IMPORTANCE_HIGH)
   ```

2. **权限时序**（第 14 轮）：`ActivityCompat.requestPermissions()` 是**异步**的——弹窗后不等待用户点击"允许"就立刻 `startForegroundService()`。此时 `POST_NOTIFICATIONS` 未授予，系统静默丢弃 `startForeground()` 的通知。
   ```kotlin
   // ❌ 旧写法：不等授权就启动
   ActivityCompat.requestPermissions(this, arrayOf(POST_NOTIFICATIONS), 1)
   startForegroundService(i)  // 通知被静默丢弃
   
   // ✅ 正确做法：registerForActivityResult + 回调中启动
   val launcher = registerForActivityResult(RequestPermission()) { granted ->
       if (granted) startOverlayServiceNow()
   }
   ```

**完整通知配置**（经验证在 HyperOS 3 上稳定置顶显示）：
```kotlin
NotificationCompat.Builder(this, chId)
    .setOngoing(true)
    .setOnlyAlertOnce(true)
    .setPriority(PRIORITY_HIGH)
    .setVisibility(VISIBILITY_PUBLIC)
    .setCategory(CATEGORY_SERVICE)
    .setForegroundServiceBehavior(FOREGROUND_SERVICE_IMMEDIATE)
```
渠道静音（无声音/无振动/无角标），避免常驻通知反复打扰。

### 2. 蒙版覆盖状态栏 / 刘海区

**表象**：`TYPE_APPLICATION_OVERLAY` + `MATCH_PARENT` 的蒙版被压在状态栏、导航栏、输入法之下，无法盖住状态栏。

**根因**：`TYPE_APPLICATION_OVERLAY` 的窗口层级本身低于状态栏，且 `MATCH_PARENT` 被系统约束到"内容区"（状态栏与导航栏之间）。

**解法**：使用**物理全屏尺寸** + `FLAG_LAYOUT_NO_LIMITS` + `LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS`：

```kotlin
// 获取物理全屏尺寸（含状态栏、导航栏、刘海）
fun realScreenSize(): Point {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val b = wm.maximumWindowMetrics.bounds
        // 注意：不是 getCurrentWindowMetrics()，那是窗口尺寸
        return Point(b.width(), b.height())
    } else {
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealSize(p)  // 不是 getSize()
    }
}
// 取最大边做正方形保证横竖屏都盖住
val side = max(real.x, real.y)
LayoutParams(side, side, TYPE_APPLICATION_OVERLAY,
    FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCHABLE
    or FLAG_LAYOUT_IN_SCREEN or FLAG_LAYOUT_NO_LIMITS,
    PixelFormat.TRANSLUCENT
).apply {
    gravity = TOP or START; x = 0; y = 0
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
        layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
}
```

### 3. 签名密钥安全 + Git 历史清理

**问题**：debug keystore 明文提交进公开仓库，任何人可 clone。

**最终方案**：
1. 用 7-Zip AES-256 加密打包 `keystore/debug-keystore.zip`
2. 密码存入 GitHub Secret（`KEYSTORE_ZIP_PASSWORD`）
3. CI 中 `7z x -p"$KS_PW"` 解密后构建
4. Git 历史中的明文已 `git filter-branch` 清出 `main`
   - 注意：游离 commit 在 GitHub 上最长保留约 90 天，彻底清除需支持工单

**关键教训**：最初「加密 zip + 密码写 workflow」的方案形同虚设——密码与 zip 在同一仓库。

### 4. APK 体积优化（5.9MB → 2.4MB）

三步见效：

| 措施 | 节省 | 说明 |
|---|---|---|
| `resConfigs("zh")` | ~1MB | Material3 自带 50+ 语言翻译，只保留中文 |
| `isMinifyEnabled=true` + `isShrinkResources=true` | ~2MB | R8 压缩代码 + 裁剪未用资源 |
| PNG → WebP 无损 | ~80KB | 10 个图标，无损格式兼容性好 |

### 5. Kotlin `max`/`min` 坑

```kotlin
// ❌ 不存在三参版本
val mx = max(r, g, b)     // 编译错误
// ❌ maxOf/minOf 在某些 Kotlin 版本（1.9.24）不可用
val mx = maxOf(r, g, b)   // Unresolved reference
// ✅ 最稳写法
val mx = max(max(r, g), b)
```
测试环境可编译通过不代表 CI 环境也能过——用兼容性最广的写法。

### 6. FAB 动画竞争

`ValueAnimator` 每次重新创建而不取消旧的，快速连续触发时多个动画同时修改 `fabParams.x`，最终位置不确定。

**修复**：保存上一次 `animator` 引用，每次开始新动画前 `cancel()` 旧的。

### 7. PanelLauncher 时间竞态

**问题**：点击通知→启动 `PanelLauncherActivity`→发广播弹面板。如果服务未运行需先启动，最初用 `Handler.postDelayed(300ms)` 猜时间——在慢设备上不可靠。

**修复**：新增 `ACTION_START_AND_PANEL` 广播，服务 `onCreate` 注册 receiver 后处理该广播自动弹面板。`startForegroundService` 同步等待 `onCreate` 完成，receiver 就绪是确定的。

### 8. 预设对分色预览实现

HSL 拖选中预设时，左半保持存储色、右半实时显示新颜色——使用 `ClipDrawable` 叠层：

```kotlin
val storedLayer = GradientDrawable().apply { setColor(storedColor) }  // 下层
val previewLayer = GradientDrawable().apply { setColor(currentColor) } // 上层
val clip = ClipDrawable(previewLayer, Gravity.RIGHT, HORIZONTAL).apply {
    level = 5000  // 右半 50%
}
btn.background = LayerDrawable(arrayOf(storedLayer, clip))
```

关键：`onStopTrackingTouch` 中不要 `buildPresetButtons()`——重建会销毁 `LayerDrawable`，预览丢失。

### 9. `isRunning` 检测

`ActivityManager.getRunningServices()` 自 Android 5.0 已废弃，在 MIUI/HyperOS 上结果不准确。

**修复**：用 `@JvmStatic var isRunning` 静态变量，`onCreate=true` / `onDestroy=false`，比反射可靠。

### 10. 三态预设槽存储

预设颜色用 `String` 存 `SharedPreferences`，而非 `Int`。原因：`Int` 默认值 0 与黑色 `0xFF000000` 混淆（在 Kotlin 中被解析为 `-16777216`），无法区分"空"和"黑色"。String 的 `null` 天然表示空。

---

## SDK / 依赖版本

| 组件 | 版本 |
|---|---|
| Kotlin | 1.9.24 |
| AGP | 8.5.2 |
| compileSdk | 35 |
| targetSdk | 35 |
| minSdk | 23 |
| Gradle | 8.9 |
| core-ktx | 1.13.1 |
| appcompat | 1.7.0 |
| material | 1.12.0 |

## 构建命令

```bash
# Debug（启用 R8 压缩 + 资源裁剪）
./gradlew assembleDebug

# 产物位置
app/build/outputs/apk/debug/app-debug.apk
```

CI 构建含固定签名 + 通知渠道初次创建，与本地构建无差异。
