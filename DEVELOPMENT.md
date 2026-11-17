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

12 条拆到 [docs/问题记录.md](docs/问题记录.md)。

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
