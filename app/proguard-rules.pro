# ProGuard rules for DarkMask (夜深模式)
# Debug 构建也启用 R8 压缩以缩小 APK，所有应用类保留原名以便调试。

-keep class com.sim.darkmask.** { *; }

# Keep Kotlin metadata (反射需要)
-keep class kotlin.Metadata { *; }

# Keep notification channel and service references (AndroidManifest 清单引用)
-keep class com.sim.darkmask.OverlayService
-keep class com.sim.darkmask.MaskTileService
-keep class com.sim.darkmask.PanelLauncherActivity
