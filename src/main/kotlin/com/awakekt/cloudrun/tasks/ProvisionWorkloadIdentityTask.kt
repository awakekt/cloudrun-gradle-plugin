package com.awakekt.cloudrun.tasks

import com.awakekt.cloudrun.CloudRunExtension
import com.awakekt.cloudrun.internal.CliRunner
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option
import org.gradle.work.DisableCachingByDefault
import java.io.File

@DisableCachingByDefault(because = "Communicates with remote Google Cloud APIs")
abstract class ProvisionWorkloadIdentityTask : DefaultTask() {

    @get:Internal
    abstract val extension: Property<CloudRunExtension>

    @get:Input
    @get:Optional
    @set:Option(option = "project", description = "GCP Project ID")
    var projectOption: String? = null

    @get:Input
    @get:Optional
    @set:Option(option = "repo", description = "GitHub repository (owner/repo)")
    var repoOption: String? = null

    @get:Input
    @get:Optional
    @set:Option(option = "region", description = "Target GCP Region")
    var regionOption: String? = null

    @get:Input
    @set:Option(option = "dryRun", description = "Simulate provisioning without modifying GCP resources")
    var dryRunOption: Boolean = false

    @get:Input
    @set:Option(option = "preview", description = "Preview provisioning commands without modifying GCP resources")
    var previewOption: Boolean = false

    @TaskAction
    fun execute() {
        val ext = extension.get()
        val isDryRun = dryRunOption || previewOption || ext.dryRun.get()

        val projectId = projectOption?.ifBlank { null }
            ?: ext.projectId.orNull?.ifBlank { null }
            ?: System.getenv("GCP_PROJECT_ID")?.ifBlank { null }
            ?: CliRunner.run("gcloud", "config", "get-value", "project", ignoreExitCode = true, printOutput = false).stdout
                .lines().firstOrNull()?.trim()?.ifBlank { null }
            ?: if (isDryRun) "dryrun-project-id" else throw GradleException("GCP Project ID is required. Specify via --project, cloudRun.projectId, or GCP_PROJECT_ID environment variable.")

        val githubRepo = repoOption?.ifBlank { null }
            ?: ext.githubRepo.orNull?.ifBlank { null }
            ?: resolveGitRemoteRepo()
            ?: if (isDryRun) "owner/repo" else throw GradleException("GitHub repository (owner/repo) is required. Specify via --repo, cloudRun.githubRepo, or ensure git remote origin is configured.")

        val region = regionOption?.ifBlank { null }
            ?: ext.region.get()

        val saName = ext.serviceAccountName.get()
        val saEmail = "$saName@$projectId.iam.gserviceaccount.com"
        val poolName = ext.workloadIdentityPool.get()
        val providerName = ext.workloadIdentityProvider.get()
        val artifactRepo = ext.artifactRepo.get()
        val providerResource = "projects/<project-number>/locations/global/workloadIdentityPools/$poolName/providers/$providerName"

        val headerSuffix = if (isDryRun) " [DRY RUN]" else ""
        println("==========================================================")
        println("🚀 Provisioning GCP Workload Identity Federation (com.awakekt.cloudrun)$headerSuffix")
        println("   Project ID     : $projectId")
        println("   GitHub Repo    : $githubRepo")
        println("   Region         : $region")
        println("   Service Account: $saName ($saEmail)")
        println("   Artifact Repo  : $artifactRepo")
        println("==========================================================")

        if (isDryRun) {
            println("[DRY RUN] Planned Actions:")
            println("   1. Set active project: gcloud config set project $projectId")
            println("   2. Enable Google Cloud APIs:")
            println("      run.googleapis.com, cloudbuild.googleapis.com, artifactregistry.googleapis.com, iam.googleapis.com, iamcredentials.googleapis.com")
            println("   3. Ensure Service Account exists: $saEmail")
            println("   4. Ensure Docker Artifact Registry repository exists: $artifactRepo in $region")
            println("   5. Assign IAM roles to $saEmail:")
            println("      - roles/run.admin")
            println("      - roles/iam.serviceAccountUser")
            println("      - roles/cloudbuild.builds.editor")
            println("      - roles/storage.admin")
            println("      - roles/artifactregistry.writer")
            println("      - roles/viewer")
            println("   6. Grant Compute Engine SA permissions for Cloud Build")
            println("   7. Ensure Workload Identity Pool exists: $poolName")
            println("   8. Ensure Workload Identity Provider exists: $providerName (with condition: assertion.repository == '$githubRepo')")
            println("   9. Bind Service Account to GitHub repository via roles/iam.workloadIdentityUser")
            println("  10. Generate local env file: ${ext.outputFile.get()} with content:")
            println("      GCP_PROJECT_ID=$projectId")
            println("      GCP_REGION=$region")
            println("      GCP_SERVICE_ACCOUNT=$saEmail")
            println("      GCP_WORKLOAD_IDENTITY_PROVIDER=$providerResource")
            println("  11. Configure GitHub repository variables via 'gh' CLI (if authenticated):")
            println("      GCP_PROJECT_ID, GCP_REGION, GCP_SERVICE_ACCOUNT, GCP_WORKLOAD_IDENTITY_PROVIDER")
            println()
            println("==========================================================")
            println("🔎 Dry Run Complete! No changes were made to GCP.")
            println("==========================================================")
            return
        }

        // Set active project
        CliRunner.run("gcloud", "config", "set", "project", projectId, "--quiet")

        // 1. Enable APIs
        println("🔌 1. Enabling required Google Cloud APIs...")
        CliRunner.run(
            "gcloud", "services", "enable",
            "run.googleapis.com",
            "cloudbuild.googleapis.com",
            "artifactregistry.googleapis.com",
            "iam.googleapis.com",
            "iamcredentials.googleapis.com",
            "--project=$projectId",
            "--quiet"
        )

        // 2. Ensure Service Account exists
        println("👤 2. Ensuring Service Account ($saName) exists...")
        val saExists = CliRunner.run(
            "gcloud", "iam", "service-accounts", "describe", saEmail,
            "--project=$projectId",
            ignoreExitCode = true,
            printOutput = false
        ).exitCode == 0

        if (!saExists) {
            CliRunner.run(
                "gcloud", "iam", "service-accounts", "create", saName,
                "--description=GitHub Actions Deployment Service Account",
                "--display-name=GitHub Actions Deployer",
                "--project=$projectId",
                "--quiet"
            )
            println("   ✅ Service Account created.")
        } else {
            println("   ℹ️ Service account already exists: $saEmail")
        }

        // 3. Ensure Artifact Registry repository exists
        println("📦 3. Ensuring Artifact Registry repository ($artifactRepo) in $region...")
        val repoExists = CliRunner.run(
            "gcloud", "artifacts", "repositories", "describe", artifactRepo,
            "--location=$region",
            "--project=$projectId",
            ignoreExitCode = true,
            printOutput = false
        ).exitCode == 0

        if (!repoExists) {
            CliRunner.run(
                "gcloud", "artifacts", "repositories", "create", artifactRepo,
                "--repository-format=docker",
                "--location=$region",
                "--description=Container repository for $saName",
                "--project=$projectId",
                "--quiet"
            )
            println("   ✅ Artifact Registry repository created: $artifactRepo")
        } else {
            println("   ℹ️ Artifact Registry repository already exists: $artifactRepo")
        }

        // 4. Assign IAM Roles to Service Account
        println("🔐 4. Assigning IAM Roles to $saEmail...")
        val saRoles = listOf(
            "roles/run.admin",
            "roles/iam.serviceAccountUser",
            "roles/cloudbuild.builds.editor",
            "roles/storage.admin",
            "roles/artifactregistry.writer",
            "roles/viewer"
        )
        for (role in saRoles) {
            println("   -> Assigning $role...")
            CliRunner.run(
                "gcloud", "projects", "add-iam-policy-binding", projectId,
                "--member=serviceAccount:$saEmail",
                "--role=$role",
                "--quiet",
                printOutput = false
            )
        }

        // 5. Grant Compute SA permissions for Cloud Build
        println("🔢 5. Resolving project metadata & granting Compute Engine SA build permissions...")
        val projectNumber = CliRunner.run(
            "gcloud", "projects", "describe", projectId,
            "--format=value(projectNumber)",
            printOutput = false
        ).stdout.trim()

        val computeSa = "$projectNumber-compute@developer.gserviceaccount.com"
        val computeRoles = listOf(
            "roles/storage.objectViewer",
            "roles/logging.logWriter",
            "roles/artifactregistry.writer"
        )
        for (role in computeRoles) {
            CliRunner.run(
                "gcloud", "projects", "add-iam-policy-binding", projectId,
                "--member=serviceAccount:$computeSa",
                "--role=$role",
                "--quiet",
                ignoreExitCode = true,
                printOutput = false
            )
        }

        // 6. Workload Identity Pool
        println("🏊 6. Ensuring Workload Identity Pool ($poolName) exists...")
        val poolExists = CliRunner.run(
            "gcloud", "iam", "workload-identity-pools", "describe", poolName,
            "--project=$projectId",
            "--location=global",
            ignoreExitCode = true,
            printOutput = false
        ).exitCode == 0

        if (!poolExists) {
            CliRunner.run(
                "gcloud", "iam", "workload-identity-pools", "create", poolName,
                "--project=$projectId",
                "--location=global",
                "--display-name=GitHub Actions Pool",
                "--quiet"
            )
            println("   ✅ Workload Identity Pool created.")
        } else {
            println("   ℹ️ Workload Identity Pool already exists: $poolName")
        }

        // 7. Workload Identity Provider
        println("🤝 7. Ensuring Workload Identity Provider ($providerName) exists...")
        val providerExists = CliRunner.run(
            "gcloud", "iam", "workload-identity-pools", "providers", "describe", providerName,
            "--project=$projectId",
            "--location=global",
            "--workload-identity-pool=$poolName",
            ignoreExitCode = true,
            printOutput = false
        ).exitCode == 0

        val condition = "assertion.repository == '$githubRepo'"
        if (!providerExists) {
            CliRunner.run(
                "gcloud", "iam", "workload-identity-pools", "providers", "create-oidc", providerName,
                "--project=$projectId",
                "--location=global",
                "--workload-identity-pool=$poolName",
                "--display-name=GitHub Actions Provider",
                "--issuer-uri=https://token.actions.githubusercontent.com",
                "--attribute-mapping=google.subject=assertion.sub,attribute.actor=assertion.actor,attribute.repository=assertion.repository",
                "--attribute-condition=$condition",
                "--quiet"
            )
            println("   ✅ Workload Identity Provider created.")
        } else {
            CliRunner.run(
                "gcloud", "iam", "workload-identity-pools", "providers", "update-oidc", providerName,
                "--project=$projectId",
                "--location=global",
                "--workload-identity-pool=$poolName",
                "--attribute-condition=$condition",
                "--quiet",
                ignoreExitCode = true,
                printOutput = false
            )
            println("   ℹ️ Workload Identity Provider updated with attribute condition.")
        }

        // 8. Workload Identity User Binding
        println("🔗 8. Binding GitHub repository to Service Account...")
        val principal = "principalSet://iam.googleapis.com/projects/$projectNumber/locations/global/workloadIdentityPools/$poolName/attribute.repository/$githubRepo"
        CliRunner.run(
            "gcloud", "iam", "service-accounts", "add-iam-policy-binding", saEmail,
            "--project=$projectId",
            "--role=roles/iam.workloadIdentityUser",
            "--member=$principal",
            "--quiet",
            printOutput = false
        )
        println("   ✅ IAM policy binding added.")

        val resolvedProviderResource = "projects/$projectNumber/locations/global/workloadIdentityPools/$poolName/providers/$providerName"

        // 9. Write local backup .env file
        val envFile = File(project.projectDir, ext.outputFile.get())
        envFile.writeText(
            """
            # Generated by com.awakekt.cloudrun on ${java.time.Instant.now()}
            GCP_PROJECT_ID=$projectId
            GCP_REGION=$region
            GCP_SERVICE_ACCOUNT=$saEmail
            GCP_WORKLOAD_IDENTITY_PROVIDER=$resolvedProviderResource
            """.trimIndent()
        )
        println("💾 Saved local backup configuration to: ${envFile.name}")

        // 10. Automatically set GitHub variables if gh is logged in
        if (CliRunner.hasCommand("gh")) {
            val ghAuth = CliRunner.run("gh", "auth", "status", ignoreExitCode = true, printOutput = false).exitCode == 0
            if (ghAuth) {
                println("🐙 9. Configuring GitHub Repository Variables via 'gh' CLI...")
                CliRunner.run("gh", "variable", "set", "GCP_PROJECT_ID", "-b", projectId, "-R", githubRepo, ignoreExitCode = true, printOutput = false)
                CliRunner.run("gh", "variable", "set", "GCP_REGION", "-b", region, "-R", githubRepo, ignoreExitCode = true, printOutput = false)
                CliRunner.run("gh", "variable", "set", "GCP_SERVICE_ACCOUNT", "-b", saEmail, "-R", githubRepo, ignoreExitCode = true, printOutput = false)
                CliRunner.run("gh", "variable", "set", "GCP_WORKLOAD_IDENTITY_PROVIDER", "-b", resolvedProviderResource, "-R", githubRepo, ignoreExitCode = true, printOutput = false)
                println("   ✅ GitHub repository variables configured for $githubRepo.")
            }
        }

        println("==========================================================")
        println("🎉 Provisioning Complete!")
        println("   Service Account  : $saEmail")
        println("   Provider Resource: $resolvedProviderResource")
        println("==========================================================")
    }

    private fun resolveGitRemoteRepo(): String? {
        val result = CliRunner.run("git", "remote", "get-url", "origin", ignoreExitCode = true, printOutput = false)
        if (result.exitCode != 0) return null
        val url = result.stdout.trim()
        val regex = Regex("""(?:github\.com[/:]|git@github\.com:)([^/]+/[^/.]+)(?:\.git)?""")
        return regex.find(url)?.groupValues?.get(1)
    }
}
