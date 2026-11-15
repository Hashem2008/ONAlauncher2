import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.onalauncher.controls"
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }

    defaultConfig {
        applicationId = "com.onalauncher.controls"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "1.2"
    }

    signingConfigs {
        // Fixed key committed with the project, so every CI build installs over the previous one.
        create("ona") {
            storeFile = file("ona_controls.jks")
            storePassword = "onacontrols"
            keyAlias = "onacontrols"
            keyPassword = "onacontrols"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("ona")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("ona")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        aidl = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3:1.5.0-beta02")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    // Shizuku (no-root shell rights): real key injection into any app
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
