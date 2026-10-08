plugins {
    `kotlin-dsl`
    `maven-publish`
}

group = "com.awakekt"
version = "1.0.1"

gradlePlugin {
    plugins {
        create("cloudRun") {
            id = "com.awakekt.cloudrun"
            implementationClass = "com.awakekt.cloudrun.CloudRunPlugin"
            displayName = "AwakeKt Cloud Run Gradle Plugin"
            description = "Zero-touch Google Cloud Run & Workload Identity Federation management for Gradle"
            tags.set(listOf("gcp", "cloud-run", "cloudrun", "workload-identity", "docker"))
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
