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
        // The published release is the default source of truth. It must stay ahead of
        // mavenLocal: Sidekit re-cuts a release tag under the same version number, and a staged
        // build would otherwise silently shadow the release files. A source build is still
        // explicit through -PsidetrackLocalSidekit=true.
        maven {
            name = "SidekitPages"
            url = uri("https://manuel-rtsch.github.io/Sidekit/maven")
            content {
                includeGroup("hrln.interactive.sidekit")
            }
        }
        mavenLocal {
            content {
                includeGroup("hrln.interactive.sidekit")
            }
        }
    }
}

rootProject.name = "Sidetrack"
include(":app")
include(":core")
include(":provider-navidrome")
include(":provider-jellyfin")
include(":provider-spotify")
// The Spotify provider module is added in its own stage.

// Release builds use the published SDK from GitHub Pages. A sibling Sidekit checkout is only a
// development shortcut and must be requested explicitly with -PsidetrackLocalSidekit=true, so a
// local checkout can never silently change what a release was built against.
val localSidekit = rootDir.parentFile.resolve("Sidekit")
val useLocalSidekit = providers.gradleProperty("sidetrackLocalSidekit")
    .map(String::toBoolean)
    .getOrElse(false)
if (useLocalSidekit && localSidekit.isDirectory) {
    includeBuild(localSidekit) {
        dependencySubstitution {
            substitute(module("hrln.interactive.sidekit:sidekit-design")).using(project(":sidekit"))
            substitute(module("hrln.interactive.sidekit:sidekit-sdk")).using(project(":sidekit-sdk"))
        }
    }
}
