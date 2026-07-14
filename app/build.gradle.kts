plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.yichuan.thermalsurvey"
    compileSdkVersion("android-36.1")
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "com.yichuan.thermalsurvey"
        minSdk = 23
        targetSdk = 35
        versionCode = 6
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
