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
  - Trusts a single repo, or a comma-separated list of repos (e.g. a canonical repo plus a CI mirror), with the same identity.
  - Configures GitHub Repository Variables automatically via `gh` CLI, for every trusted repo.
  - Saves `.env` local backup file.
- **Day-1 Fast Deployment (`deployCloudRun`)**:
  - Automatically depends on `buildFatJar`, `shadowJar`, or `bootJar`.
  - Submits the project's own directory (or `sourceDir`) to Cloud Build, which builds the `Dockerfile` at its root, and deploys the container to Cloud Run.
  - Automatically applies performance and stability flags (`--no-cpu-throttling`, `--no-invoker-iam-check`, `--allow-unauthenticated`), and `--cpu-boost` when `cpuBoost` is true.
- **Service provisioning (`provisionCloudRunService`)**:
  - Enables the APIs the service needs, creates each `secret(...)` in Secret Manager and stores a version from the environment variable of the same name.
  - Grants the runtime account `secretAccessor` on each secret, `serviceAccountTokenCreator` on itself when `signsUrls = true`, and the deploy account the roles a deploy needs.
  - Idempotent, and fails when a secret ends up with no enabled version.
  - Applies an Artifact Registry cleanup policy that keeps the newest `keepImages` images of the service (default 3) and deletes older ones, so deploys stay inside the free storage allowance.
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
    id("com.awakekt.cloudrun") version "1.2.0"
}

cloudRun {
    projectId.set("my-gcp-project")
    region.set("asia-southeast1") // Default
    serviceName.set("my-api")
    artifactRepo.set("my-api-repo")
    githubRepo.set("owner/repo")
    // Or trust more than one repo with the same identity, e.g. a canonical repo plus a CI mirror:
    // githubRepo.set("owner/repo,other-owner/mirror-repo")
    
    port.set(8080)
    minInstances.set(1)
    maxInstances.set(3)
    memory.set("1Gi")
    cpu.set("1")
    // Set only to change Cloud Run's defaults (80 requests, 300 s); left unset, the flags stay off the deploy.
    concurrency.set(40)
    timeout.set(java.time.Duration.ofMinutes(10))
    // Extra CPU while an instance starts (--cpu-boost). Left false, the flag stays off the deploy.
    cpuBoost.set(true)
    noCpuThrottling.set(true)
    allowUnauthenticated.set(true)
    noInvokerIamCheck.set(true)

    // Folder uploaded to Cloud Build; it must hold the Dockerfile. Defaults to this project's directory.
    sourceDir.set(layout.projectDirectory)
    // Identity the service runs as; without it Cloud Run uses the Compute Engine default account.
    runtimeServiceAccount.set("my-api@my-gcp-project.iam.gserviceaccount.com")

    // provisionCloudRunService: grant the runtime account serviceAccountTokenCreator on itself (to sign URLs),
    // and the deploy account roles beyond the ones every deploy needs.
    signsUrls.set(true)
    extraDeployRoles.add("roles/datastore.indexAdmin")
    // provisionCloudRunService: keep this many of the service's newest images in artifactRepo, delete older ones.
    // The default is 3; 0 applies no policy.
    keepImages.set(3)

    env("APP_ENV", "production")
    env("SERVER_TIMEZONE", "Asia/Manila")
    // Secret Manager reference (SECRET_NAME:VERSION), never the value itself.
    secret("API_KEY", "my-api-key:latest")
}
```

Environment variables reach `gcloud run deploy` through a temporary `--env-vars-file`, so values may hold
commas and never appear on the command line. Like `--set-env-vars`, it replaces every variable the service had.

---

## Available Tasks

| Task | Description | Command |
|---|---|---|
| `provisionGcpWorkloadIdentity` | One-click setup of WIF, IAM, and Artifact Registry. | `./gradlew provisionGcpWorkloadIdentity --repo="owner/repo"` (or `--repo="owner/repo,other-owner/mirror-repo"` for multiple) |
| `provisionCloudRunService` | Enables APIs, stores `secret(...)` values from the environment, and grants the runtime and deploy accounts their roles. | `API_KEY=... ./gradlew provisionCloudRunService` |
| `deployCloudRun` | Compiles fat JAR, builds container, and deploys to Cloud Run. | `./gradlew deployCloudRun` |
| `mapCustomDomain` | Maps custom domain to Cloud Run service. | `./gradlew mapCustomDomain --domain=api.example.com` |

---

## Provisioning a service

Run `provisionGcpWorkloadIdentity` once for the deploy account, then `provisionCloudRunService` before the first deploy.
For each `secret("API_KEY", "api-key:latest")` it creates the secret `api-key` and stores the value of the environment
variable `API_KEY` as a new version, but only when the value differs from the latest, so running it again changes nothing.
Values reach `gcloud` on stdin: never on a command line, in a log or in `--dryRun` output, which prints `********` instead.
A variable that is not set leaves the secret's versions as they are; a secret that then has no enabled version fails the
task, since Cloud Run starts no revision without one. The runtime account is `runtimeServiceAccount`, or the Compute Engine
default account when unset; the deploy account is `serviceAccountName` in the project. It is granted `run.admin`,
`cloudbuild.builds.editor`, `serviceusage.serviceUsageConsumer` and `storage.admin` on the project, `extraDeployRoles`, and
`serviceAccountUser` on the runtime account.

### Image cleanup

Every deploy pushes one image (tagged with the git SHA) to `artifactRepo` and nothing removes the old ones, so storage
grows past Artifact Registry's free allowance. With `keepImages = N` (default 3), `provisionCloudRunService` runs
`gcloud artifacts repositories set-cleanup-policies` on `artifactRepo` with two policies named after the service:

```json
[
  {"name": "keep-newest-<service>", "action": {"type": "KEEP"},
   "mostRecentVersions": {"packageNamePrefixes": ["<service>"], "keepCount": N}},
  {"name": "delete-older-<service>", "action": {"type": "DELETE"},
   "condition": {"tagState": "ANY", "packageNamePrefixes": ["<service>"]}}
]
```

A keep policy wins over a delete policy, so the newest N versions stay and the rest go. Artifact Registry applies cleanup
policies in a periodic background job, so old images disappear within about a day. Notes:

- Re-running changes nothing: the repository's policies are read first, and nothing is set when these two already match.
  Policies other services put on the same repository are written back untouched.
- `packageNamePrefixes` is a prefix match, so a service named `api` also covers a package `api-worker` in the same repository.
- `keepImages = 0` (or no value) applies no policy. It does not remove one applied earlier; use
  `gcloud artifacts repositories delete-cleanup-policies`.
- The policy is applied active (`--no-dry-run`). `--dryRun` on the task is the plugin's simulation and prints the policy.
- Keep at least 2 so a rollback target survives: a revision whose image was deleted cannot start new instances.
- Setting the policy needs `artifactregistry.repositories.update` (`roles/artifactregistry.repoAdmin`) on the repository,
  which the account running `provisionCloudRunService` has; the deploy account does not need it.

## Dry-Run & Simulation Mode

Preview all GCP commands, parameters, IAM role bindings, container image URIs, and environment variables without modifying any cloud infrastructure:

### Via Command-Line Flag:
```bash
# Preview deployment
./gradlew deployCloudRun --dryRun

# Preview provisioning
./gradlew provisionGcpWorkloadIdentity --dryRun

# Preview secrets and IAM (values masked)
./gradlew provisionCloudRunService --dryRun

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
