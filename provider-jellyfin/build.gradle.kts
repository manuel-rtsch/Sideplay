import java.util.Properties

val fallbackVersionName = "0.1.0"

// Mirrors :app's git-derived version so the Jellyfin client reports the same version the app does.
fun git(vararg args: String) = providers.exec {
    commandLine("git", *args)
    workingDir = rootDir
    isIgnoreExitValue = true
}.standardOutput.asText.map(String::trim)

val gitVersionName = git("describe", "--tags", "--match", "v[0-9]*", "--dirty")
    .map { described ->
        val name = described.removePrefix("v")
        if (name.isBlank() || !name.first().isDigit()) fallbackVersionName else name
    }
fun versionNameFromGit() = runCatching { gitVersionName.get() }.getOrDefault(fallbackVersionName)

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    // Unique per module; :app owns io.github.manuelrtsch.sidetrack. Sources keep their packages.
    namespace = "io.github.manuelrtsch.sidetrack.jellyfin"
    compileSdk {
        version = release(37)
    }
    defaultConfig {
        minSdk = 31
        // JellyfinAuth reports the client version to the server through BuildConfig.VERSION_NAME.
        // The library plugin does not drive versionName, so the value is mirrored from the app's
        // git-derived version to keep the two reporting the same client version.
        buildConfigField("String", "VERSION_NAME", "\"${versionNameFromGit()}\"")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        buildConfig = true
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    api(project(":core"))
    implementation(libs.jellyfin.core)
    implementation(libs.sidekit.sdk)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    // The opt-in integration test needs ApplicationProvider under Robolectric.
    testImplementation(libs.androidx.junit)
    // On the JVM the SDK's kotlin-logging resolves the slf4j backend, which the android runtime
    // never loads; without it every real-server sign-in fails with NoClassDefFoundError.
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.1")
}
