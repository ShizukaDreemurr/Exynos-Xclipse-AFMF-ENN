plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.fluidframes.enn"
    compileSdk = 36

    defaultConfig {
        minSdk = 31
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        // LiteRT 2.3.x dựng bằng Kotlin metadata 2.4; compiler của ta là 2.0.21.
        freeCompilerArgs += "-Xskip-metadata-version-check"
    }
}

dependencies {
    implementation(project(":core-telemetry"))
    // LiteRT (TFLite mới) CompiledModel API. Sau lần sync đầu tiên, ghim phiên bản cụ thể thay cho "+".
    implementation("com.google.ai.edge.litert:litert:2.3.2")
    implementation("com.google.ai.edge.litert:litert-gpu:2.3.2")
    // Runtime NPU Samsung (LiteCore) chưa có tọa độ Maven xác nhận — xem docs/ENN_SDK_GUIDE.md.
    // Khi có AAR/Maven, thêm vào đây hoặc đặt .aar trong core-enn/libs/ và bật dòng dưới.
    // implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))
}
