import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val fallbackVersionName = "0.1.0"
val fallbackVersionCode = 1

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
val gitVersionCode = git("rev-list", "--count", "HEAD")
    .map { it.toIntOrNull() ?: fallbackVersionCode }

fun versionCodeFromGit() = runCatching { gitVersionCode.get() }.getOrDefault(fallbackVersionCode)
fun versionNameFromGit() = runCatching { gitVersionName.get() }.getOrDefault(fallbackVersionName)

// Release signing is configured locally: keystore.properties is gitignored and points at the
// dedicated Sidetrack release certificate. Without it, release stays unsigned so CI can sign.
val releaseKeystorePropertiesFile = rootProject.file("keystore.properties")
val releaseKeystoreProperties = Properties().apply {
    if (releaseKeystorePropertiesFile.isFile) {
        releaseKeystorePropertiesFile.inputStream().use(::load)
    }
}

// `-PsidetrackStandaloneDemo=true` turns `assembleDemo` into a second, co-installable app:
// suffix `.demo` on the application id, its own Sidekit app id and label, so the host shows both
// the release app and the fake-catalog build. The plain demo build keeps the release identity the
// Maestro flows and promo recordings pin.
val standaloneDemo = providers.gradleProperty("sidetrackStandaloneDemo")
    .map(String::toBoolean)
    .getOrElse(false)

android {
    ndkVersion = "27.1.12297006"
    namespace = "io.github.manuelrtsch.sidetrack"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "io.github.manuelrtsch.sidetrack"
        minSdk = 31
        targetSdk = 36
        versionCode = providers.gradleProperty("sidetrackVersionCode")
            .map(String::toInt)
            .getOrElse(versionCodeFromGit())
        versionName = providers.gradleProperty("sidetrackVersionName")
            .getOrElse(versionNameFromGit())

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // The About screen reports the Sidekit SDK version the app was built against. Kept in
        // sync with the version catalog so it cannot drift from the actual dependency.
        buildConfigField("String", "SIDEKIT_VERSION", "\"${libs.versions.sidekit.get()}\"")

        // The `demo` build type serves a fixed catalog instead of the signed-in account's
        // Spotify library. It is never part of a release build.
        buildConfigField("boolean", "DEMO_CONTENT", "false")
        buildConfigField("String", "DEMO_PACK", "\"none\"")

        // App identity the manifest and the OAuth landing page read. Overridden per build type when
        // a build has to sit next to the release app instead of replacing it.
        manifestPlaceholders["appLabel"] = "@string/app_name"
        manifestPlaceholders["sidekitAppId"] = "sidetrack"
        manifestPlaceholders["authScheme"] = "sidetrack"
        buildConfigField("String", "AUTH_SCHEME", "\"sidetrack\"")
    }

    signingConfigs {
        if (releaseKeystorePropertiesFile.isFile) {
            create("release") {
                storeFile = file(releaseKeystoreProperties.getProperty("storeFile"))
                storePassword = releaseKeystoreProperties.getProperty("storePassword")
                keyAlias = releaseKeystoreProperties.getProperty("keyAlias")
                keyPassword = releaseKeystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            if (releaseKeystorePropertiesFile.isFile) signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            optimization {
                enable = true
            }
        }
        create("profile") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
        create("dev") {
            initWith(getByName("profile"))
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            matchingFallbacks += listOf("release")
        }
        // Promo/demo builds. Same application id and signing as release on purpose: Sidetrack is
        // host-backed only when Sidekit trusts the release certificate, and the Maestro flows pin
        // the production application id. Installing this build replaces the release app; the
        // catalog is fixed and the account is only used for playback.
        // With -PsidetrackStandaloneDemo=true it instead becomes `…sidetrack.demo` with its own
        // Sidekit app id, so it co-installs with release and gets its own host tile and config.
        // It stays signed with the release key, so Sidekit still trusts it.
        create("demo") {
            initWith(getByName("release"))
            versionNameSuffix = "-demo"
            matchingFallbacks += listOf("release")
            if (standaloneDemo) {
                applicationIdSuffix = ".demo"
                manifestPlaceholders["appLabel"] = "Sidetrack Demo"
                manifestPlaceholders["sidekitAppId"] = "sidetrack-demo"
                manifestPlaceholders["authScheme"] = "sidetrack-demo"
                buildConfigField("String", "AUTH_SCHEME", "\"sidetrack-demo\"")
            }
            buildConfigField("boolean", "DEMO_CONTENT", "true")
            // `real` (default) shows the real-song pack, `fictional` the original backup pack.
            buildConfigField(
                "String",
                "DEMO_PACK",
                "\"${(project.findProperty("demoPack") as String?) ?: "real"}\""
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.merges += setOf("META-INF/NOTICE.md", "META-INF/LICENSE.md", "META-INF/LICENSE.txt", "META-INF/NOTICE.txt")
    }
    sourceSets["main"].jniLibs.directories.add(layout.buildDirectory.dir("generated/spotifyJniLibs").get().asFile.absolutePath)
    // The demo catalog is a fictional pack from the screenshot suite, so it reuses the mock
    // cover art that lives in the debug resources. The drawables are only ever packaged in
    // debug and demo builds.
    sourceSets["demo"].res.srcDir("src/debug/res")
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
            all {
                it.systemProperty("roborazzi.test.record", "true")
            }
        }
    }

    lint {
        // AGP, Kotlin, and the AndroidX libraries are pinned to the Sidekit toolchain. Version
        // availability is reviewed with that toolchain, not on every Sidetrack lint run. targetSdk
        // stays at 36 until the Sidekit host has been tested against 37.
        disable += setOf("AndroidGradlePluginVersion", "GradleDependency", "NewerVersionAvailable", "OldTargetApi")
    }
}

// Sidetrack's librespot bridge. The generated ABI directory is a normal jniLibs source, so an
// unsupported ABI simply has no .so and the Kotlin bridge reports it as unavailable.
val spotifyJniLibs = layout.buildDirectory.dir("generated/spotifyJniLibs")
// Native playback is opt-in: CI has no Rust toolchain or NDK, and probing Cargo at
// configuration time would break Gradle's configuration cache. Use
// `-PsidetrackNative=true` after installing both.
val cargo = listOf(
    File(System.getProperty("user.home"), ".cargo/bin/cargo"),
    File("/usr/bin/cargo"),
    File("/usr/local/bin/cargo")
).firstOrNull { it.canExecute() }
val nativeEnabled = providers.gradleProperty("sidetrackNative")
    .map(String::toBoolean)
    .orElse(providers.environmentVariable("SIDETRACK_NATIVE").map(String::toBoolean))
    .getOrElse(false)

// Resolve the NDK only when Cargo is present. CI has no Rust toolchain, and asking Gradle for
// the NDK directory eagerly would fail configuration even though the task is disabled.
val ndk = if (!nativeEnabled || cargo == null) {
    null
} else {
    runCatching { androidComponents.sdkComponents.ndkDirectory.get().asFile }.getOrNull()?.takeIf { it.isDirectory }
}

// Rust bakes absolute source paths into panic messages and into the `protobuf` build-script output:
// the checkout path (which carries this repository's directory name) and the Cargo registry (which
// carries the builder's home directory). Remap both, so the shipped library holds no
// machine-specific path and reads the same whatever the checkout is called.
val remapPrefixes = listOf(
    rootProject.projectDir.absolutePath to "/sidetrack",
    (System.getenv("CARGO_HOME")?.let(::File) ?: File(System.getProperty("user.home"), ".cargo"))
        .absolutePath to "/cargo"
)
val rustFlags = buildList {
    System.getenv("RUSTFLAGS")?.takeIf { it.isNotBlank() }?.let(::add)
    remapPrefixes.forEach { (from, to) -> add("--remap-path-prefix=$from=$to") }
}.joinToString(" ")

val buildSpotifyNative = tasks.register<Exec>("buildSpotifyNative") {
    description = "Build libsidetrack_spotify.so for arm64-v8a and x86_64."
    group = "native"
    enabled = nativeEnabled && cargo != null && ndk != null
    when {
        !nativeEnabled -> logger.lifecycle("Sidetrack: native playback is off; pass -PsidetrackNative=true to build libsidetrack_spotify.so.")
        cargo == null -> logger.warn("Sidetrack: cargo is unavailable; libsidetrack_spotify.so will not be packaged.")
        ndk == null -> logger.warn("Sidetrack: the Android NDK is unavailable; libsidetrack_spotify.so will not be packaged.")
    }
    workingDir = rootProject.file("native/sidetrack-spotify")
    commandLine(
        cargo?.absolutePath.orEmpty(),
        "ndk",
        "-t", "arm64-v8a",
        "-t", "x86_64",
        "-o", spotifyJniLibs.get().asFile.absolutePath,
        "build", "--release"
    )
    if (ndk != null) environment("ANDROID_NDK_HOME", ndk.absolutePath)
    environment("RUSTFLAGS", rustFlags)
    inputs.dir(rootProject.file("native/sidetrack-spotify/src"))
    inputs.files(rootProject.file("native/sidetrack-spotify/Cargo.toml"), rootProject.file("native/sidetrack-spotify/Cargo.lock"))
    outputs.dir(spotifyJniLibs)
}

tasks.matching {
    it.name.startsWith("merge") && (it.name.endsWith("NativeLibs") || it.name.endsWith("JniLibFolders"))
}.configureEach {
    dependsOn(buildSpotifyNative)
}

dependencies {
    implementation(libs.sidekit.design)
    implementation(libs.sidekit.sdk)
    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)

    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
