plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "nl.meereboer.ebikelive.wear"
    compileSdk = 35
    defaultConfig {
        applicationId = "nl.meereboer.ebikelive"
        minSdk = 31
        targetSdk = 34
        versionCode = 3
        versionName = "0.3-debug"
    }
    signingConfigs {
        val ks = rootProject.file("debug.keystore")
        if (ks.exists()) getByName("debug") { storeFile = ks }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":core"))
    implementation("com.google.android.gms:play-services-wearable:18.2.0")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.wear.compose:compose-material:1.4.0")
    implementation("androidx.wear.compose:compose-foundation:1.4.0")
    implementation("androidx.wear:wear:1.3.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
}
