# AwakeKt Cloud Run Gradle Plugin (`com.awakekt.cloudrun`)

A zero-touch Gradle plugin for Google Cloud Run deployment and Workload Identity Federation provisioning.

Designed for Kotlin and JVM backend applications (Ktor, Spring Boot, Micronaut, Quarkus).

---

## Features

- **Day-0 Zero-Touch Provisioning (`provisionGcpWorkloadIdentity`)**:
  - Automatically provisions Google Cloud Workload Identity Federation (WIF) for GitHub Actions.
  - Automatically creates Service Account with least-privilege IAM roles (`run.admin`, `artifactregistry.writer`, `viewer`, etc.).
  - Grants required Cloud Build permissions to Default Compute Engine Service Account.
  - Creates regional Artifact Registry repository.
  - Configures GitHub Repository Variables automatically via `gh` CLI.
  - Saves `.env` local backup file.
- **Day-1 Fast Deployment (`deployCloudRun`)**:
  - Automatically depends on `buildFatJar`, `shadowJar`, or `bootJar`.
  - Submits to Cloud Build and deploys container to Cloud Run.
  - Automatically applies performance and stability flags (`--no-cpu-throttling`, `--no-invoker-iam-check`, `--allow-unauthenticated`).
- **Domain Mapping (`mapCustomDomain`)**:
  - Maps custom domains to Cloud Run services in supported regions (`asia-southeast1`, etc.).

---

## Installation

### In `settings.gradle.kts`:
```kotlin
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
```

### In `build.gradle.kts`:
```kotlin
plugins {
    id("com.awakekt.cloudrun") version "1.0.0"
}

cloudRun {
    projectId.set("my-gcp-project")
    region.set("asia-southeast1") // Default
    serviceName.set("my-api")
    artifactRepo.set("my-api-repo")
    githubRepo.set("owner/repo")
    
    port.set(8080)
    minInstances.set(1)
    maxInstances.set(3)
    memory.set("1Gi")
    cpu.set("1")
    noCpuThrottling.set(true)
    allowUnauthenticated.set(true)
    noInvokerIamCheck.set(true)
    
    env("APP_ENV", "production")
    env("SERVER_TIMEZONE", "Asia/Manila")
}
```

---

## Available Tasks

| Task | Description | Command |
|---|---|---|
| `provisionGcpWorkloadIdentity` | One-click setup of WIF, IAM, and Artifact Registry. | `./gradlew provisionGcpWorkloadIdentity` |
| `deployCloudRun` | Compiles fat JAR, builds container, and deploys to Cloud Run. | `./gradlew deployCloudRun` |
| `mapCustomDomain` | Maps custom domain to Cloud Run service. | `./gradlew mapCustomDomain --domain=api.example.com` |

---

## Dry-Run & Simulation Mode

Preview all GCP commands, parameters, IAM role bindings, container image URIs, and environment variables without modifying any cloud infrastructure:

### Via Command-Line Flag:
```bash
# Preview deployment
./gradlew deployCloudRun --dryRun

# Preview provisioning
./gradlew provisionGcpWorkloadIdentity --dryRun

# Preview custom domain mapping
./gradlew mapCustomDomain --domain=api.example.com --dryRun
```

### Via Gradle Property:
```bash
./gradlew deployCloudRun -PdryRun
```

### Via Extension DSL:
```kotlin
cloudRun {
    dryRun.set(true)
}
```

> **Note:** Gradle's built-in `--dry-run` (`-m`) CLI flag skips all task executions globally. Use `--dryRun` or `-PdryRun` to invoke the plugin's simulation mode.

---

## Publishing to Gradle Plugin Portal

```bash
./gradlew :cloudrun-gradle-plugin:publishPlugins
```
