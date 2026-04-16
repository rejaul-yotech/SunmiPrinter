// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
    id("maven-publish")
}

// One-shot task: publishes the bundled Sunmi AAR to Maven Local so :sdk can resolve it.
// Run once on each dev machine: ./gradlew publishSunmiPublicationToMavenLocal
publishing {
    publications {
        create<MavenPublication>("sunmi") {
            groupId    = "com.sunmi"
            artifactId = "externalprinterlibrary2"
            version    = "1.0.14"
            artifact(rootProject.file("app/libs/externalprinterlibrary2-1.0.14-release.aar"))
        }
    }
    repositories {
        mavenLocal()
    }
}