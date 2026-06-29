# Xposed-GodMode ProGuard 规则
# 保留 Xposed 入口类，防止混淆后 LSPosed 无法识别模块入口
-keep class com.kaisar.xposed.godmode.injection.HookLauncher{*;}
