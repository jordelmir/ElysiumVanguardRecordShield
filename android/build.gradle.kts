// ============================================================================
// ELYSIUM VANGUARD RECORD SHIELD — Project-Level Build Configuration
// ============================================================================
// Why separate build files: The project-level file manages plugins and their
// versions globally. The app-level file applies them. This prevents version
// conflicts when you add library modules (e.g., a :core or :domain module).
// ============================================================================

plugins {
    // Android Gradle Plugin — controls the entire Android build pipeline
    id("com.android.application") version "8.7.3" apply false
    
    // Kotlin for Android — the language compiler plugin
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    
    // Compose Compiler — required since Kotlin 2.0 (moved out of Kotlin plugin)
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0" apply false
    
    // KSP (Kotlin Symbol Processing) — compile-time code generation for Room, Hilt
    // Why KSP over KAPT: KSP is 2x faster than KAPT and natively supports
    // Kotlin without requiring Java stub generation.
    id("com.google.devtools.ksp") version "2.1.0-1.0.29" apply false
    
    // Hilt — dependency injection plugin
    id("com.google.dagger.hilt.android") version "2.54" apply false
    
    // Kotlin Serialization — JSON parsing for API communication
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.0" apply false
}
