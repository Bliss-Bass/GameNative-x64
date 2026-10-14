pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
                // Hilt and KSP plugin markers are on Maven Central, not dl.google.com.
                // Leaving them inside this filter makes Google the only repo Gradle searches.
                excludeGroupByRegex("com\\.google\\.dagger.*")
                excludeGroupByRegex("com\\.google\\.devtools.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://central.sonatype.com/repository/maven-snapshots/") } // JavaSteam
    }
}

rootProject.name = "gamenative"
include(":app")
include(":ubuntufs")
