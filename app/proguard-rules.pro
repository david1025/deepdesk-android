# --- kotlinx.serialization ---
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.dsh.deepseek.**$$serializer { *; }
-keepclassmembers class com.dsh.deepseek.** { *** Companion; }
-keepclasseswithmembers class com.dsh.deepseek.** { kotlinx.serialization.KSerializer serializer(...); }

# --- OkHttp / Retrofit ---
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-keepattributes Signature, Exceptions
-keepclasseswithmembers class * { @retrofit2.http.* <methods>; }

# --- Markdown / Compose Multiplatform 渲染器 ---
-keep class com.mikepenz.markdown.** { *; }
-dontwarn com.mikepenz.markdown.**

# 诊断日志中禁止出现 API Key（CryptoManager 已从日志排除密钥；此处再防字符串混淆优化）
-assumenosideeffects class com.dsh.deepseek.core.common.AppLog {
    public static void d(...);
    public static void v(...);
}
