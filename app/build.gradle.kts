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
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    // Existing MATRIX AmneziaWG backend is retained and remains responsible for VPN execution.
    implementation("com.zaneschepke:amneziawg-android:2.3.7")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.3")
    testImplementation("junit:junit:4.13.2")
}
