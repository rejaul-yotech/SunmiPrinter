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
        mavenLocal()
        google()
        mavenCentral()

        // GitHub Packages — resolves com.yotech:valtprinter-sdk and com.sunmi:externalprinterlibrary2.
        // Credentials read from ~/.gradle/gradle.properties (gpr.user / gpr.token)
        // or environment variables GPR_USER / GPR_TOKEN.
        val gprUser = providers.gradleProperty("gpr.user").orNull
            ?: System.getenv("GPR_USER") ?: ""
        val gprToken = providers.gradleProperty("gpr.token").orNull
            ?: System.getenv("GPR_TOKEN") ?: ""
        if (gprUser.isNotEmpty() && gprToken.isNotEmpty()) {
            maven {
                name = "GitHubPackages"
                url  = uri("https://maven.pkg.github.com/rejaul-yotech/SunmiPrinter")
                credentials {
                    username = gprUser
                    password = gprToken
                }
            }
        }
    }
}

rootProject.name = "Valt Printer"
include(":app")
include(":sdk")
 