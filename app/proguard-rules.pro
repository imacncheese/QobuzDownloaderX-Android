# ---------------------------------------------------------------------------
# QobuzDLX Android
# ---------------------------------------------------------------------------

# JAudioTagger reaches into format internals and registers services by class name.
-keep class org.jaudiotagger.** { *; }
-dontwarn org.jaudiotagger.**
-dontwarn java.awt.**
-dontwarn javax.imageio.**
-dontwarn javax.swing.**
-dontwarn org.jaudiotagger.tag.images.**

# OkHttp / Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.qbdlx.mobile.api.** {
    *** Companion;
}
-keepclasseswithmembers class com.qbdlx.mobile.api.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.qbdlx.mobile.api.**$$serializer { *; }
-keepclassmembers class com.qbdlx.mobile.api.** {
    *** Companion;
    *** serializer(...);
}

# Keep enum values used by the download queue's persistence-free state.
-keepclassmembers enum com.qbdlx.mobile.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
