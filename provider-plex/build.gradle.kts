import kotlinx.kover.gradle.plugin.dsl.AggregationType
import kotlinx.kover.gradle.plugin.dsl.CoverageUnit

val fallbackVersionName = "0.1.0"

// Mirrors :app's git-derived version so Plex sees the same client version the app reports.
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
    alias(libs.plugins.kover)
}

// `*IntegrationTest` classes need a live server; opt in with -PsidetrackIntegration.
val runIntegrationTests = providers.gradleProperty("sidetrackIntegration").isPresent

android {
    // Unique per module; :app owns io.github.manuelrtsch.sidetrack. Sources keep their packages.
    namespace = "io.github.manuelrtsch.sidetrack.plex"
    compileSdk {
        version = release(37)
    }
    defaultConfig {
        minSdk = 31
        // PlexAuth reports the client version to plex.tv through BuildConfig.VERSION_NAME. The
        // library plugin does not drive versionName, so mirror the app's git-derived version.
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
            all {
                // `*IntegrationTest` classes need a live server; opt in with
                // -PsidetrackIntegration instead of silently skipping them in the default run.
                if (!runIntegrationTests) it.filter.excludeTestsMatching("*IntegrationTest")
            }
        }
    }
}

dependencies {
    api(project(":core"))
    implementation(libs.sidekit.sdk)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.junit)
}

// Coverage ratchet for this module's own tests. The floor starts below the first measured value;
// it only ever moves up as the adapter gains tests.
kover {
    reports {
        variant("debug") {
            verify {
                rule("line coverage") {
                    minBound(55)
                }
                rule("branch coverage") {
                    minBound(35, CoverageUnit.BRANCH, AggregationType.COVERED_PERCENTAGE)
                }
            }
        }
    }
}
