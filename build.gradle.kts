plugins {
    alias(libs.plugins.android.application) apply false
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
