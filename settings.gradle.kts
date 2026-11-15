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

rootProject.name = "Sidetrack"
include(":app")

// During Sidekit development, build against the sibling checkout so changes (such as the restored
// dictation field) are available without publishing a release. CI and clones without that checkout
// keep using the published SDK from GitHub Pages. Disable explicitly with -PsidetrackLocalSidekit=false.
val localSidekit = rootDir.parentFile.resolve("Sidekit")
val useLocalSidekit = providers.gradleProperty("sidetrackLocalSidekit")
    .map(String::toBoolean)
    .getOrElse(localSidekit.isDirectory)
if (useLocalSidekit && localSidekit.isDirectory) {
    includeBuild(localSidekit) {
        dependencySubstitution {
            substitute(module("hrln.interactive.sidekit:sidekit-design")).using(project(":sidekit"))
            substitute(module("hrln.interactive.sidekit:sidekit-sdk")).using(project(":sidekit-sdk"))
        }
    }
}
