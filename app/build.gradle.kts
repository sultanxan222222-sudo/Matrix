plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "org.matrixvpn"
    compileSdk = 36
    defaultConfig {
        applicationId = "org.matrixvpn.tv"
        minSdk = 26
        targetSdk = 28
        versionCode = 8
        versionName = "0.6.0-caravan-tv"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}
dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    // Existing WireGuard / AmneziaWG tunnel backend.
    implementation("com.zaneschepke:amneziawg-android:2.3.7")
    // Pinned GPL-licensed OpenVPN for Android engine; source and native libraries
    // are supplied by the OpenGateVPN submodule (see THIRD_PARTY_NOTICES.md).
    implementation(project(":openvpnEngine"))
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.3")
    testImplementation("junit:junit:4.13.2")
}
