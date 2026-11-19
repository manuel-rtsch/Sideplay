plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    // Unique per module; :app owns io.github.manuelrtsch.sidetrack. Sources keep their
    // io.github.manuelrtsch.sidetrack.* packages; this namespace only drives generated code.
    namespace = "io.github.manuelrtsch.sidetrack.navidrome"
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
