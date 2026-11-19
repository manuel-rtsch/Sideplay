plugins {
    alias(libs.plugins.android.library)
}

android {
    // Unique per module; :app owns io.github.manuelrtsch.sidetrack. Sources keep their
    // io.github.manuelrtsch.sidetrack.* packages; this namespace only drives generated code.
    namespace = "io.github.manuelrtsch.sidetrack.spotify"
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
    // The provider implements the neutral contracts from :core and nothing from :app.
    api(project(":core"))
    implementation(libs.sidekit.sdk)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    // ApplicationProvider and AndroidJUnit4 in the session, auth and cache tests.
    testImplementation(libs.androidx.junit)
}
