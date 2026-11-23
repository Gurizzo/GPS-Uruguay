plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.radiohonda.radaruy"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.radiohonda.radaruy"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        // Solo procesadores de celulares (sin x86 de emuladores): ahorra ~27 MB
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Para uso personal: se firma con la clave de debug
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

    // El mapa y los límites se leen directo del APK, sin comprimir
    testOptions {
        unitTests.all { it.maxHeapSize = "2g"; it.jvmArgs("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8"); it.testLogging { showStandardStreams = true } }
    }

    androidResources {
        noCompress += listOf("pmtiles", "bin")
    }
}

dependencies {
    implementation("org.maplibre.gl:android-sdk:11.13.5")
    implementation("androidx.car.app:app:1.4.0")
    implementation("androidx.core:core-ktx:1.13.1")
    testImplementation("junit:junit:4.13.2")
}
