plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "br.unioeste.pulselab.wear"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "br.unioeste.pulselab.wear"
        minSdk = 30
        // targetSdk 35 (not 36): on Wear OS 6 / API 36, BODY_SENSORS is deprecated
        // and heart rate requires the granular health permissions (READ_HEART_RATE)
        // via Health Services. Targeting 35 keeps the classic BODY_SENSORS runtime
        // flow working with plain SensorManager. See Wear OS 6 behavior changes.
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    useLibrary("wear-sdk")
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    // Wear OS + Compose
    implementation(libs.play.services.wearable)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.compose.material3)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.compose.ui.tooling)
    implementation(libs.androidx.wear.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.splashscreen)

    // ViewModel — bridges UI state to business logic
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Coroutines — needed for suspend functions and Flow
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    // Gson — serializes sensor payloads sent over the Wear Data Layer
    implementation(libs.gson)

    // Health Services — background heart rate via PassiveMonitoringClient.
    // concurrent-futures-ktx provides ListenableFuture.await() for the async API.
    implementation(libs.androidx.health.services.client)
    implementation(libs.androidx.concurrent.futures.ktx)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
