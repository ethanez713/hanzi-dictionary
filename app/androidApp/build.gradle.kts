plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.hanzidictionary.app"
    // compileSdk 35 required by requery 3.49.0 (its AAR sets minCompileSdk=35). 35 also
    // brings the 16 KB-page toolchain; needs AGP 8.6+ (see root build.gradle.kts).
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hanzidictionary.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1-alpha"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    }

    composeOptions {
        // Compose compiler compatible with Kotlin 1.9.24.
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    // SQLite packs are already-compact binary (don't recompress).
    androidResources {
        noCompress += listOf("db")
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(project(":core"))

    implementation("androidx.core:core-ktx:1.13.1")

    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    implementation("app.cash.sqldelight:android-driver:2.0.2")

    // Hourly tick behind the study reminder. WorkManager (not AlarmManager) because the
    // reminder is deliberately inexact, and it re-arms itself across reboots with no
    // BOOT_COMPLETED receiver to maintain.
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Android's framework SQLite is not built with SQLITE_ENABLE_FTS5 (confirmed absent
    // even on the S25 / Android 16), so the pack's glyph_fts MATCH query throws
    // "no such module: fts5". requery ships a modern SQLite .so compiled with FTS5
    // (-DSQLITE_ENABLE_FTS5 in its Android.mk); we point the pack drivers at it via
    // RequerySQLiteOpenHelperFactory. Distributed via JitPack (see settings.gradle.kts).
    // 3.49.0 ships FTS5 with 16 KB-page-aligned native libs (required by Android 15/16;
    // 3.45.0's libsqlite3x.so was not aligned and triggered a compat warning). Needs
    // compileSdk 35 (minCompileSdk in its AAR metadata).
    implementation("com.github.requery:sqlite-android:3.49.0")

    // Unbundled ML Kit Chinese text recognition (model downloaded via Play Services, keeps
    // the APK small per spec §9/§13). Its Task API is consumed via blocking Tasks.await
    // (play-services-tasks, pulled in transitively).
    implementation("com.google.android.gms:play-services-mlkit-text-recognition-chinese:16.0.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
