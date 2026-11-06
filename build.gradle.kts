plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
}

// The upstream OpenVPN Android library contains Kotlin support code but its
// legacy vpnLib Gradle file only declares the Android library plugin.
// Apply Kotlin Android when that plugin is present so ProfileEncryption.kt
// is compiled as part of the embedded engine.
gradle.beforeProject {
    if (path == ":openvpnEngine") {
        pluginManager.withPlugin("com.android.library") {
            pluginManager.apply("org.jetbrains.kotlin.android")
        }
    }
}
