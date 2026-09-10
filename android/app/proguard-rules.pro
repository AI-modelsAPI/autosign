# AutoSign R8 混淆规则
# 目标：代码混淆 + 体积优化，同时保住三个反射/桥接点

# 1. WebView JS 桥：AuthActivity$Bridge 的方法名被 JS 以 window.JustSign.onFill() 调用，
#    混淆后名字变了 JS 就调不到 → 必须保留 @JavascriptInterface 注解方法
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# 2. WorkManager Worker：框架通过反射实例化 CheckWorker，类名/构造器不能混淆
-keep class icu.justwoker.justsign.Engine$CheckWorker {
    public <init>(...);
}

# 3. Activity/Service 等组件由 manifest 引用，AGP 自动生成 keep 规则，无需手写

# 4. org.json / okhttp / webkit 由 consumer rules 覆盖，无需重复

# 优化：关闭无用的日志调用可进一步瘦身，但 opLogs 是用户可见的操作日志（业务数据），
# 不是 android.util.Log，不受此影响；保留 android.util.Log 调用（崩溃排查需要）
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**

# 混淆字典：用 a,b,c... 短名，最大化压缩
-optimizationpasses 5
-allowaccessmodification
-repackageclasses ''
