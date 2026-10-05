// Toolchain chosen to build on Android Studio versions that support AGP up to 9.1
// (and on newer ones such as Quail 4): AGP 9.1.0 + Gradle 9.3.1 (see gradle/wrapper).
// AGP 9 compiles Kotlin itself ("built-in Kotlin"), so the org.jetbrains.kotlin.android plugin
// is NOT applied. The Kotlin Gradle plugin is pinned to the same version as the Compose
// compiler plugin, because the two must match.
buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.20")
    }
}

plugins {
    id("com.android.application") version "9.1.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20" apply false
}
