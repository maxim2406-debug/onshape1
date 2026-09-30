# kotlinx.serialization: keep generated serializers of app models
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class com.ration.app.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class com.ration.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Release: strip verbose/debug/info logs (only WARN and above remain)
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# Сборка api: Anthropic Java SDK (Jackson + OkHttp) использует рефлексию
-keep class com.anthropic.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keepattributes Signature, EnclosingMethod
-dontwarn com.fasterxml.jackson.**
-dontwarn org.slf4j.**
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
