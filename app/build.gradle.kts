plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "fi.marketwatcher"
    compileSdk = 36            // Compose 1.11 (BOM 2026.06.01); Compose 1.12 would need 37 + AGP 9.1.1

    defaultConfig {
        applicationId = "fi.marketwatcher"
        minSdk = 26
        targetSdk = 36
        versionCode = 16
        versionName = "0.16-personal"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isReturnDefaultValues = true }

    // Market data (S&P 500, VIX, NFCI history, Shiller CAPE, golden test data) is copyrighted, so it
    // lives in the private MarketWatcher-data repository, cloned next to this project. Without it the
    // app still builds (no bundled 2007–2016 history) and the data-based unit tests are skipped.
    val data = rootProject.file("../MarketWatcher-data")
    sourceSets {
        getByName("main") { assets.srcDir(data.resolve("assets")) }
        getByName("test") {
            resources.srcDir(data.resolve("test-resources"))
            resources.srcDir(data.resolve("assets"))
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")   // real org.json for the JVM golden test
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
