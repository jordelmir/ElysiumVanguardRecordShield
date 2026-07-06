// ============================================================================
// ELYSIUM VANGUARD RECORD SHIELD — App-Level Build Configuration
// ============================================================================
// Why Kotlin DSL: Type-safe, IDE-assisted, and the modern standard for
// Android projects. Groovy DSL is legacy.
//
// Architecture Decision: We use the latest stable versions of all libraries
// as of 2025. Version catalog (libs.versions.toml) is NOT used here for
// clarity — each dependency is explicitly versioned with comments explaining
// why it's included.
// ============================================================================

import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.elysium.vanguard.recordshield"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.elysium.vanguard.recordshield"
        minSdk = 26  // Why 26: CameraX video capture requires API 26+. Also gives us
                     // access to Autofill, Notification Channels, and PiP natively.
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0-alpha"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Why: Build config field for the Vercel API URL.
        // This allows switching between dev/staging/prod without code changes.
        buildConfigField("String", "VERCEL_API_URL", "\"https://your-project.vercel.app\"")

        // Load OAuth credentials from local.properties (gitignored)
        val localPropsFile = rootProject.file("local.properties")
        val oauthClientId: String
        val oauthClientSecret: String
        if (localPropsFile.exists()) {
            val stream = localPropsFile.inputStream()
            val props = Properties()
            props.load(stream)
            stream.close()
            oauthClientId = props.getProperty("oauth.client.id", "")
            oauthClientSecret = props.getProperty("oauth.client.secret", "")
        } else {
            oauthClientId = ""
            oauthClientSecret = ""
        }
        buildConfigField("String", "OAUTH_CLIENT_ID", "\"$oauthClientId\"")
        buildConfigField("String", "OAUTH_CLIENT_SECRET", "\"$oauthClientSecret\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Why: Allows the app to handle large video chunks without OOM
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/LICENSE"
            excludes += "/META-INF/LICENSE.txt"
            excludes += "/META-INF/NOTICE"
            excludes += "/META-INF/NOTICE.txt"
        }
    }

    lint {
        baseline = file("lint-baseline.xml")
        abortOnError = false
        xmlReport = true
        htmlReport = true
    }
}

// Forward tmpdir to KSP workers (Room SQLite verifier needs writable temp)
val projectTmpDir = "${project.projectDir}/.tmp"
tasks.configureEach {
    if (this is JavaForkOptions) {
        jvmArgs("-Djava.io.tmpdir=$projectTmpDir", "-Dorg.sqlite.tmpdir=$projectTmpDir")
    }
}

ksp {
    arg("room.schemaLocation", "${projectDir}/schemas")
}

dependencies {
    // ========================================================================
    // JETPACK COMPOSE — UI Framework
    // ========================================================================
    // Why BOM: Ensures all Compose dependencies use compatible versions,
    // preventing version mismatch crashes.
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // Core Compose UI
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Material Design 3 — Neo-futuristic design system foundation
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Animation — For glow pulses, matrix rain, and organic transitions
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.animation:animation-graphics")

    // Compose Runtime (Flow integration)
    implementation("androidx.compose.runtime:runtime")

    // Activity + Lifecycle integration with Compose
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    // ViewModel & Lifecycle
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-common-java8:2.8.7")
    implementation("androidx.core:core-splashscreen:1.0.1")

    // Navigation for Compose — Screen routing
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // ========================================================================
    // CAMERAX — Video & Photo Capture Pipeline
    // ========================================================================
    // Why CameraX over Camera2: CameraX provides a lifecycle-aware, 
    // use-case-driven API that handles device-specific quirks automatically.
    // Camera2 would require 3x more boilerplate for the same functionality.
    val cameraXVersion = "1.4.1"
    implementation("androidx.camera:camera-core:$cameraXVersion")
    implementation("androidx.camera:camera-camera2:$cameraXVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraXVersion")
    implementation("androidx.camera:camera-video:$cameraXVersion")
    implementation("androidx.camera:camera-view:$cameraXVersion")

    // ========================================================================
    // MEDIA3 (ExoPlayer) — Video Playback
    // ========================================================================
    // Why Media3 over legacy ExoPlayer: Media3 is the official successor,
    // providing a unified API for playback, sessions, and UI. Legacy
    // ExoPlayer is deprecated.
    // 
    // The PlayerView will use RESIZE_MODE_FILL as required: stretching
    // video to fill every pixel without maintaining aspect ratio.
    val media3Version = "1.5.1"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")
    implementation("androidx.media3:media3-session:$media3Version")

    // ========================================================================
    // KTOR — HTTP Client for Vercel API Communication
    // ========================================================================
    // Why Ktor over Retrofit: Ktor is Kotlin-first, supports coroutines
    // natively, and handles binary uploads more elegantly than Retrofit's
    // multipart API. For chunked binary uploads, Ktor's streaming body
    // support is superior.
    val ktorVersion = "3.0.3"
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-okhttp:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-client-logging:$ktorVersion")

    // ========================================================================
    // ROOM — Local SQLite Database for Metadata
    // ========================================================================
    // Why Room: Provides compile-time SQL verification, Flow/coroutine
    // integration, and migration support. Stores local recording metadata
    // and upload queue state for offline resilience.
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // ========================================================================
    // DATASTORE — Encrypted Key-Value Storage
    // ========================================================================
    // Why DataStore over SharedPreferences: DataStore is async, type-safe,
    // and handles concurrent access correctly. Combined with Security Crypto,
    // it stores the PIN hash and API keys securely.
    implementation("androidx.datastore:datastore-preferences:1.1.2")

    // ========================================================================
    // SECURITY — Encrypted Storage
    // ========================================================================
    // Why Security Crypto: Provides EncryptedSharedPreferences and
    // EncryptedFile using Android Keystore + AES-256-GCM. Stores the
    // device API key and PIN hash with hardware-backed encryption.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // ========================================================================
    // HILT — Dependency Injection
    // ========================================================================
    // Why Hilt over Koin/manual DI: Hilt provides compile-time DI
    // verification, lifecycle-aware scoping (ViewModel, Activity, Service),
    // and is the official Android DI solution from Google.
    val hiltVersion = "2.54"
    implementation("com.google.dagger:hilt-android:$hiltVersion")
    ksp("com.google.dagger:hilt-compiler:$hiltVersion")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // ========================================================================
    // WORKMANAGER — Background Upload Retry
    // ========================================================================
    // Why WorkManager: Guarantees chunk uploads complete even if the app
    // is backgrounded or the device reboots. Uses exponential backoff
    // for network failures. This is critical for evidence preservation.
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.hilt:hilt-work:1.2.0")
    ksp("androidx.hilt:hilt-compiler:1.2.0")

    // ========================================================================
    // KOTLIN COROUTINES — Async Operations
    // ========================================================================
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // ========================================================================
    // GOOGLE PLAY SERVICES — Drive API + Auth
    // ========================================================================
    // Why Play Services Auth: OAuth2 flow for Google Drive access.
    // The device gets a scoped token to upload files to the user's Drive.
    implementation("com.google.android.gms:play-services-auth:21.3.0")

    // Why Google API Client: Legacy but stable Drive API access.
    // Combined with REST API calls for actual file uploads.
    implementation("com.google.api-client:google-api-client:2.7.2")
    implementation("com.google.oauth-client:google-oauth-client-jetty:1.36.0")

    // ========================================================================
    // CORE ANDROID
    // ========================================================================
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")

    // ========================================================================
    // TESTING
    // ========================================================================
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
