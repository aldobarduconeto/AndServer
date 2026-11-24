plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.andserver"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.andserver"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Permite que o APK de release seja assinado e instalável diretamente para distribuição
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}
