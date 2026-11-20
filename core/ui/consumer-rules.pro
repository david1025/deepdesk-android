# Markdown 渲染器（Compose Multiplatform 发布物）保留规则
-keep class com.mikepenz.markdown.** { *; }
-keep class ru.noties.jlatexmath.** { *; }
-keepclassmembers class com.david.deepdesk.core.ui.MermaidBridge {
    @android.webkit.JavascriptInterface <methods>;
}
