plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.andserver"
    compileSdk = 35

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        applicationId = "com.andserver"
        minSdk = 26
        targetSdk = 35
        versionCode = providers.gradleProperty("VERSION_CODE").getOrElse("1").toInt()
        versionName = providers.gradleProperty("VERSION_NAME").getOrElse("1.0")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            val keystorePath = providers.environmentVariable("ANDROID_KEYSTORE_PATH").orNull
            val storePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orNull
            val keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").orNull
            val keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").orNull
            signingConfig = if (listOf(keystorePath, storePassword, keyAlias, keyPassword).all { !it.isNullOrBlank() }) {
                signingConfigs.create("releaseDistribution") {
                    storeFile = file(keystorePath!!)
                    this.storePassword = storePassword
                    this.keyAlias = keyAlias
                    this.keyPassword = keyPassword
                }
            } else signingConfigs.getByName("debug")
        }
    }
}
