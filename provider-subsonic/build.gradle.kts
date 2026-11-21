import kotlinx.kover.gradle.plugin.dsl.AggregationType
import kotlinx.kover.gradle.plugin.dsl.CoverageUnit

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kover)
}

// `*IntegrationTest` classes need a live server; opt in with -PsidetrackIntegration.
val runIntegrationTests = providers.gradleProperty("sidetrackIntegration").isPresent

android {
    // Unique per module; :app owns io.github.manuelrtsch.sidetrack. Sources keep their
    // io.github.manuelrtsch.sidetrack.* packages; this namespace only drives generated code.
    namespace = "io.github.manuelrtsch.sidetrack.subsonic"
    compileSdk {
        version = release(37)
    }
    defaultConfig {
        minSdk = 31
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
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
    // NavidromeApiTest launches under Robolectric and needs ApplicationProvider.
    testImplementation(libs.androidx.junit)
}

// Coverage ratchet for this module's own tests. The floor is the level measured when Kover was
// introduced (docs/test-coverage-audit.md); it only ever moves up. Note this measures what this
// module's tests cover, not what the whole suite covers -- the product-wide gate lives in :app,
// which is the merging module.
kover {
    reports {
        variant("debug") {
            verify {
                rule("line coverage") {
                    minBound(68)
                }
                rule("branch coverage") {
                    minBound(41, CoverageUnit.BRANCH, AggregationType.COVERED_PERCENTAGE)
                }
            }
        }
    }
}
