plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.bilal.meowbot"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bilal.meowbot"
        minSdk = 30 // Erişilebilirlik ile ekran görüntüsü Android 11+ gerektirir
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    // Sabit imza: yeni sürümü kurarken eskisini silmek gerekmesin
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
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

dependencies {
    implementation(project(":core"))
}
