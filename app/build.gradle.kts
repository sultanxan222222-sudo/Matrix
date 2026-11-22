plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "org.matrixvpn"
    compileSdk = 36
    androidResources {
        // The bundled quantized model is already compressed; avoid wasting CPU
        // recompressing 425 MiB during APK build and first-launch asset reads.
        noCompress += listOf("bin")
    }
    ndkVersion = "27.2.12479018"
    defaultConfig {
        applicationId = "org.matrixvpn"
        minSdk = 26
        targetSdk = 35
        versionCode = 16
        versionName = "0.8.2"
        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild {
            cmake { arguments += listOf("-DANDROID_STL=c++_shared") }
        }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}
dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    // Embeddable AmneziaWG tunnel library; supports AWG configuration extensions
    // while retaining the WireGuard Android backend/config API used by MATRIX.
    implementation("com.zaneschepke:amneziawg-android:2.3.7")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
