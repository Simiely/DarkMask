# 深色蒙版（DarkMask）· 适配澎湃 OS 3

一个在小米/红米 **HyperOS 3（Android 15/16）** 上运行的全屏降亮蒙版工具：
靠边自动隐藏的悬浮按钮控制开关，支持任意颜色（默认最深黑）与 **5%–95% 无极透明度**，
并针对小米「激进杀后台」做了前台服务 + 保活引导。

---

## 一、已实现功能（任务清单 · 已完成）

| # | 功能 | 说明 |
|---|------|------|
| 1 | 全屏降亮蒙版 | `TYPE_APPLICATION_OVERLAY` 全屏绘制，`FLAG_NOT_TOUCHABLE` 触摸穿透，不影响正常使用手机 |
| 2 | 靠边隐藏悬浮按钮 | 拖动到左/右边缘自动缩进，仅留 35% 作为「把手」，点按即可唤回 |
| 3 | 一键开关蒙版 | 点按悬浮钮 = 开/关；长按悬浮钮 = 打开控制面板 |
| 4 | 默认最深黑色 | 默认颜色 `#000000`，预设 纯黑/暖光/护眼/夜红/深蓝 + RGB 自定义 |
| 5 | 5%–95% 无极透明度 | SeekBar 滑轨，`max=90` 映射为 `进度+5`，杜绝 <5% 看不见 / >95% 刺眼 |
| 6 | 前台服务保活 | `foregroundServiceType="specialUse"` + 常驻通知，降低被系统回收概率 |
| 7 | 通知栏快捷磁贴 | `MaskTileService` 一键切换，状态实时同步 |
| 8 | 自动夜间 | 22:00–07:00 自动开启蒙版（每分钟检测），白天自动关闭 |
| 9 | 隐藏悬浮按钮 | 开关：仅用通知/磁贴/主界面控制 |
| 10 | 权限引导 | 主界面显示悬浮窗/电池/通知三态，一键跳转设置、小米权限页 |
| 11 | 位置记忆 | 悬浮钮位置写入 `SharedPreferences`，重启后恢复 |
| 12 | 控制面板退出 | 面板内「退出程序」按钮，彻底停止服务、清除蒙版与通知，无残留 |

---

## 二、优化与补充功能（任务清单 · 待做 / 可扩展）

- [ ] **应用白名单**：基于 `UsageStatsManager` 检测前台 App，对指定 App（如相机、相册）自动关闭蒙版
- [ ] **亮度联动**：系统亮度低于阈值时自动加深蒙版（需读取系统亮度）
- [ ] **多档预设一键切换**：阅读 / 观影 / 睡眠 三套参数，通知栏或悬浮菜单快捷切换
- [ ] **定时开关**：自定义起止时间（取代固定 22:00–07:00）
- [ ] **悬浮钮外观自定义**：大小、透明度、图标、是否显示「当前透明度」角标
- [ ] **边缘手势**：从屏幕边缘向内滑唤出控制面板（替代长按）
- [ ] **防误关**：磁贴/通知二次确认，避免误触关掉蒙版导致黑夜刺眼
- [ ] **省电优化**：仅在蒙版开启时运行定时检测，关闭后停止 `Handler` 轮询
- [ ] **多语言 / 无障碍**：跟随系统、TalkBack 描述
- [ ] **崩溃兜底**：`WindowManager` 添加失败时降级提示，而非白屏

---

## 三、HyperOS 3 保活指引（关键！不配置会被杀后台）

小米/红米在 HyperOS 3 下会激进杀后台，为保证运行时蒙版不被回收，请：

1. 打开本应用 → 授予「悬浮窗」权限（主界面按钮跳转）。
2. 设置 → 应用设置 → 深色蒙版 → 省电策略设为「无限制」。
3. 多任务界面长按本应用 → 锁定（🔒），防止被划掉。
4. 运行时不会被系统回收；**退出请点控制面板/通知的「退出」**，服务彻底停止、不留残留（无需开启「自启动」）。

> 说明：`SYSTEM_ALERT_WINDOW`、`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 在 HyperOS 上常被收纳在
> 「手机管家 / 安全中心」中，应用内跳转不可用时会回退到应用详情页手动开启。

---

## 四、构建与运行

### 方式 A：Android Studio（推荐）
1. 用 Android Studio 打开 `DarkMask/` 根目录（会自动生成 Gradle Wrapper 并下载依赖）。
2. 连接已开启「USB 调试」的 HyperOS 3 设备，或启动模拟器。
3. 菜单 `Build → Make Project`，无误后 `Run 'app'`。
4. 首次进入按提示授予「悬浮窗」权限，点「启动蒙版」。

### 方式 B：命令行（需本地已装 Android SDK + Gradle）
```bash
cd DarkMask
./gradlew assembleDebug        # 生成 app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 环境要求
- `compileSdk = 35` / `targetSdk = 35`（Android 15，适配 HyperOS 3）
- `minSdk = 23`（Android 6.0，悬浮窗权限起点）
- Kotlin 1.9.24 / AGP 8.5.2 / JDK 17
- 依赖：`androidx.core-ktx`、`appcompat`、`material(Material3)`

---

## 五、工程结构

```
DarkMask/
├── app/build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
└── app/src/main/
    ├── AndroidManifest.xml
    ├── java/com/sim/darkmask/
    │   ├── MainActivity.kt        # 权限引导 + 完整设置 + 保活指引
    │   ├── OverlayService.kt      # 前台服务：蒙版 + 悬浮钮 + 控制面板
    │   ├── MaskTileService.kt     # 通知栏快捷磁贴
    │   └── Prefs.kt               # 轻量偏好存储
    └── res/
        ├── layout/  (activity_main / fab_button / control_panel)
        ├── drawable/ (图标 + 形状)
        └── values/  (strings / colors / themes)
```

> 配色沿用你偏好的 **哔哩哔哩粉 `#FB7299`** + 深色科技风。
