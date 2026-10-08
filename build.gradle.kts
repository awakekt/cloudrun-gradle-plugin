plugins {
    `kotlin-dsl`
    `maven-publish`
    id("com.gradle.plugin-publish") version "1.3.1"
}

group = "com.awakekt"
version = "1.0.2"

gradlePlugin {
    website.set("https://github.com/awakekt/cloudrun-gradle-plugin")
    vcsUrl.set("https://github.com/awakekt/cloudrun-gradle-plugin.git")
    plugins {
        create("cloudRun") {
            id = "com.awakekt.cloudrun"
            implementationClass = "com.awakekt.cloudrun.CloudRunPlugin"
            displayName = "AwakeKt Cloud Run Gradle Plugin"
            description = "Zero-touch Google Cloud Run & Workload Identity Federation management for Gradle"
            tags.set(listOf("gcp", "cloud-run", "cloudrun", "workload-identity", "kotlin", "serverless"))
        }
    }
}

repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    testImplementation(kotlin("test"))
}
