plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val ksPass: String? = System.getenv("KEYSTORE_PASSWORD")

android {
    namespace = "vn.nguyenvanhoa.camerastampgps"
    compileSdk = 34

    defaultConfig {
        applicationId = "vn.nguyenvanhoa.camerastampgps"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "1.2"
    }

    // Khóa ký cố định: mọi bản build có cùng chữ ký -> cài đè/cập nhật được
    signingConfigs {
        create("release") {
            storeFile = rootProject.file("keystore/camerastampgps.jks")
            storePassword = ksPass
            keyAlias = "camerastampgps"
            keyPassword = ksPass
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (!ksPass.isNullOrBlank()) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    val camerax = "1.3.4"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    implementation("com.google.android.gms:play-services-location:21.3.0")
}
