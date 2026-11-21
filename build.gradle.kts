plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.spotless) apply false
}

val ktlintRulesFromEditorConfig: Map<String, String> =
    providers.fileContents(rootProject.layout.projectDirectory.file(".editorconfig"))
        .asText
        .map { text ->
            text.lines()
                .map { it.substringBefore('#').trim() }
                .filter { '=' in it }
                .map { it.split('=', limit = 2).map(String::trim) }
                .filter { (key, _) -> key == "max_line_length" || key.startsWith("ktlint_") }
                .associate { (key, value) -> key to value }
        }
        .get()

allprojects {
    apply(plugin = "com.diffplug.spotless")

    configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        ratchetFrom(providers.gradleProperty("spotlessRatchet").getOrElse("origin/main"))
        kotlin {
            target("src/**/*.kt")
            ktlint(libs.versions.ktlint.get()).editorConfigOverride(ktlintRulesFromEditorConfig)
        }
        kotlinGradle {
            target("*.gradle.kts")
            ktlint(libs.versions.ktlint.get()).editorConfigOverride(ktlintRulesFromEditorConfig)
        }
    }
}

// One entry point for the coverage gate. `:app` is the merging module, so its rule is the
// product-wide floor; the other four verify their own modules. Add `-PsidetrackScreenshots`
// for the full suite, which has the higher floor.
tasks.register("coverage") {
    group = "verification"
    description = "Runs the unit tests and verifies every coverage floor."
    dependsOn(
        ":app:koverVerifyDebug",
        ":app:koverHtmlReportDebug",
        ":app:koverXmlReportDebug",
        ":core:koverVerifyDebug",
        ":provider-spotify:koverVerifyDebug",
        ":provider-jellyfin:koverVerifyDebug",
        ":provider-subsonic:koverVerifyDebug"
    )
}
