pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        mavenLocal {
            content {
                includeGroup("hrln.interactive.sidekit")
            }
        }
        maven {
            name = "SidekitPages"
            url = uri("https://manuel-rtsch.github.io/Sidekit/maven")
            content {
                includeGroup("hrln.interactive.sidekit")
            }
        }
    }
}

rootProject.name = "Sideplay"
include(":app")

// Temporary until the SDK artifacts are published: CI checks out Sidekit and points this at the
// checkout, so the app builds against the same source the artifacts will come from.
providers.environmentVariable("SIDEKIT_PROJECT_DIR").orNull?.let { path ->
    includeBuild(path) {
        dependencySubstitution {
            substitute(module("hrln.interactive.sidekit:sidekit-design")).using(project(":sidekit"))
            substitute(module("hrln.interactive.sidekit:sidekit-sdk")).using(project(":sidekit-sdk"))
        }
    }
}
