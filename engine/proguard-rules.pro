# 规则引擎模块的 ProGuard 规则
# 保留 JSON 序列化相关的字段名
-keepclassmembers class com.kaisar.xposed.godmode.engine.rule.RuleMatchSpec {
    <fields>;
}
