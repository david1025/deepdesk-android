# Markdown 渲染器（Compose Multiplatform 发布物）保留规则
-keep class com.mikepenz.markdown.** { *; }
-keep class ru.noties.jlatexmath.** { *; }
# 方框先描边再画字。绘制时要按字段名把画笔改回填充，字段名不能被改掉。
-keepclassmembers class ru.noties.jlatexmath.awt.AndroidGraphics2D {
    private android.graphics.Paint paint;
}
# 公式引擎用字符串注册 aligned、cases、pmatrix。混淆会把这些环境剪掉，发布包就只剩源码。
-keep class org.scilab.forge.jlatexmath.** { *; }
-keepclassmembers class com.david.deepdesk.core.ui.MermaidBridge {
    @android.webkit.JavascriptInterface <methods>;
}
