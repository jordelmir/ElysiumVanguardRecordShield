# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in the SDK tools proguard-defaults.txt.

# ============================================================================
# KTOR CLIENT
# ============================================================================
# Why: Ktor uses reflection for engine initialization and serialization.
-keepattributes *Annotation*
-keepclassmembers class io.ktor.** { *; }
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**

# ============================================================================
# KOTLINX SERIALIZATION
# ============================================================================
# Why: Serialization uses reflection to instantiate @Serializable classes.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.elysium.vanguard.recordshield.**$$serializer { *; }
-keepclassmembers class com.elysium.vanguard.recordshield.** { *** Companion; }
-keepclasseswithmembers class com.elysium.vanguard.recordshield.** { kotlinx.serialization.KSerializer serializer(...); }

# ============================================================================
# ROOM
# ============================================================================
# Why: Room generates DAO implementations at compile time, but needs runtime reflection for some operations.
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# ============================================================================
# HILT
# ============================================================================
# Why: Hilt generates inject-able factories that must survive proguard.
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }
