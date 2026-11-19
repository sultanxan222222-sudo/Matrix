plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "org.matrixvpn"
    compileSdk = 36
    defaultConfig {
        applicationId = "org.matrixvpn"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "0.5.1"
        ndkVersion = "27.2.12479018"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild {
            cmake {
                // clvk's OpenCL runtime depends on the shared Android C++ runtime.
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
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
    // Keep AmneziaWG/WireGuard and add an independent OpenVPN 3 engine for VPN Gate profiles.
    implementation("com.zaneschepke:amneziawg-android:2.3.7")
    implementation("io.github.tim06:openvpn:1.1.3")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.3")
    testImplementation("junit:junit:4.13.2")
    // Use a real JSON implementation in local JVM tests instead of Android's "not mocked" stubs.
    testImplementation("org.json:json:20240303")
}
