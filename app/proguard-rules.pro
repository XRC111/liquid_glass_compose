# Compose 相关
-dontwarn org.jetbrains.annotations.**

# 保留 AGSL / 渲染相关（虽然无反射调用，显式声明避免误优化）
-keepclassmembers class * {
    native <methods>;
}
