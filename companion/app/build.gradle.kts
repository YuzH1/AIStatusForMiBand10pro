import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val keystoreProperties = Properties().apply {
    val f = rootProject.file("signing/keystore.properties")
    if (f.exists()) load(FileInputStream(f))
}

android {
    namespace = "com.ivy.aiquota"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ivy.aiquota"
        minSdk = 24
        targetSdk = 34
        versionCode = 10
        versionName = "1.0.10"
    }

    signingConfigs {
        create("release") {
            if (keystoreProperties.isNotEmpty()) {
                storeFile = rootProject.file(keystoreProperties.getProperty("store.file"))
                storePassword = keystoreProperties.getProperty("store.password")
                keyAlias = keystoreProperties.getProperty("key.alias")
                keyPassword = keystoreProperties.getProperty("key.password")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("release")
        }
        getByName("release") {
            isMinifyEnabled = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    implementation(files("libs/xms-wearable-lib_1.4_release.aar"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}