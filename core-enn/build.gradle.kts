plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.fluidframes.enn"
    compileSdk = 35

    defaultConfig {
        minSdk = 31
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":core-telemetry"))
    // LiteRT (TFLite mới) CompiledModel API. Sau lần sync đầu tiên, ghim phiên bản cụ thể thay cho "+".
    implementation("com.google.ai.edge.litert:litert:+")
    implementation("com.google.ai.edge.litert:litert-gpu:+")
    // Runtime NPU Samsung (LiteCore) chưa có tọa độ Maven xác nhận — xem docs/ENN_SDK_GUIDE.md.
    // Khi có AAR/Maven, thêm vào đây hoặc đặt .aar trong core-enn/libs/ và bật dòng dưới.
    // implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))
}
