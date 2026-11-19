import kotlinx.kover.gradle.plugin.dsl.AggregationType
import kotlinx.kover.gradle.plugin.dsl.CoverageUnit

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kover)
}

android {
    // Unique per module: :app already owns io.github.manuelrtsch.sidetrack. Core sources keep their
    // io.github.manuelrtsch.sidetrack.* packages; this namespace only drives generated R/BuildConfig,
    // and core has neither (its R/buildConfig usage stays with the app, where the resources live).
    namespace = "io.github.manuelrtsch.sidetrack.core"
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
        }
    }
}

dependencies {
    implementation(libs.sidekit.sdk)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    // ApplicationProvider and the JUnit4 runner used by the store and progress tests.
    testImplementation(libs.androidx.junit)

    androidTestImplementation(libs.androidx.junit)
}

// Coverage ratchet for this module's own tests. The floor is the level measured when Kover was
// introduced (docs/test-coverage-audit.md); it only ever moves up. Note this measures what this
// module's tests cover, not what the whole suite covers -- the product-wide gate lives in :app,
// which is the merging module.
kover {
    reports {
        variant("debug") {
            verify {
                // Phase 2 slice 1 (audio focus and the runtime contract seams) raised the
                // module from 35.8% / 29.0% to 46.5% / 34.0%.
                rule("line coverage") {
                    minBound(46)
                }
                rule("branch coverage") {
                    minBound(34, CoverageUnit.BRANCH, AggregationType.COVERED_PERCENTAGE)
                }
            }
        }
    }
}
