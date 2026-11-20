plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val reportRelayUrl = System.getenv("REPORT_RELAY_URL")?.trim()?.takeIf { it.isNotEmpty() }
    ?: "https://light-report-relay.glupo.workers.dev/report"

android {
    namespace = "com.gios.brightfantasy"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.gios.brightfantasy"
        minSdk = 29
        targetSdk = 35
        // CI stamps versionCode from the workflow run number and appends it to the
        // major.minor below, so the release tag is <major>.<minor>.<run>. Bump this by
        // hand for anything Obtainium should treat as a new version.
        versionCode = 1
        versionName = "1.0.0"

        // Shake-to-report posts to the relay in gi-os/BrightCommon (relay/), which holds the key
        // that files the issue, so no credential ships in the APK. Blank queues reports.
        buildConfigField("String", "REPORT_RELAY_URL", "\"$reportRelayUrl\"")
        buildConfigField("String", "REPORT_REPO", "\"gi-os/light-reports\"")

        // The LPIII is arm64 only; shipping four ABIs tripled the APK for nothing.
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    sourceSets {
        getByName("main") { java.srcDirs("src/main/kotlin") }
        getByName("test") { java.srcDirs("src/test/kotlin") }
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.savedstate:savedstate:1.2.1")

    // Networking. ESPN's fantasy API is cookie-authenticated; see FantasyApi.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // The credential QR. CameraX for the preview and the analysis stream, ZXing core for
    // the decode — ML Kit's barcode model is tens of megabytes bundled and this phone has
    // no Play Services to fetch it from, whereas zxing-core is about half a megabyte of
    // pure Java that decodes straight off the Y plane.
    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    implementation("com.google.zxing:core:3.5.3")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
