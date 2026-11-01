plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.fluidframes.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.fluidframes"
        minSdk = 31
        targetSdk = 37
        val run = (System.getenv("GITHUB_RUN_NUMBER") ?: "0").toIntOrNull() ?: 0
        val sha = (System.getenv("GITHUB_SHA") ?: "local").take(7)
        versionCode = 1 + run
        versionName = "0.3.$run"
        buildConfigField("String", "BUILD_TAG", "\"v0.3.$run·$sha\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures { compose = true; buildConfig = true }

    packaging { jniLibs { useLegacyPackaging = true; pickFirsts += "**/libLiteRt.so" } }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":core-capture"))
    implementation(project(":core-telemetry"))
    implementation(project(":core-overlay"))
    implementation(project(":core-vulkan"))
    implementation(project(":core-enn"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
