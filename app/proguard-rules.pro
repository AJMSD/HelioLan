# ProGuard rules for HelioLAN

# Keep application class
-keep class com.heliolan.app.HelioLanApplication { *; }

# Kotlin
-dontwarn kotlin.**
-keepclassmembers class **$WhenMappings {
    <fields>;
}
-keepclassmembers class kotlin.Metadata {
    public <methods>;
}

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.** {
    volatile <fields>;
}

# Hilt
-dontwarn com.google.errorprone.annotations.**
-keep class dagger.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Health Connect
-keep class androidx.health.connect.client.** { *; }
-keep interface androidx.health.connect.client.** { *; }

# Ktor
-keep class io.ktor.** { *; }
-keep class kotlin.reflect.jvm.internal.** { *; }
-dontwarn io.ktor.**
-dontwarn org.slf4j.**
-dontwarn org.fusesource.**

# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.heliolan.**$$serializer { *; }
-keepclassmembers class com.heliolan.** {
    *** Companion;
}
-keepclasseswithmembers class com.heliolan.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Moshi
-keepclasseswithmembers class * {
    @com.squareup.moshi.* <methods>;
}
-keep @com.squareup.moshi.JsonQualifier interface *
-keepclassmembers class kotlin.Metadata {
    public <methods>;
}

# ZXing
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# SQLCipher
-keep class net.sqlcipher.** { *; }
-dontwarn net.sqlcipher.**

# Keep all model classes (data classes for serialization)
-keep class com.heliolan.data.model.** { *; }
-keep class com.heliolan.server.model.** { *; }

# General
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses
