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

@DisableCachingByDefault(because = "Deploys to remote Google Cloud Run")
abstract class DeployCloudRunTask : DefaultTask() {

    @get:Internal
    abstract val extension: Property<CloudRunExtension>

    @get:Input
    @get:Optional
    @set:Option(option = "tag", description = "Container image tag (defaults to git SHA or 'latest')")
    var tagOption: String? = null

    @get:Input
    @set:Option(option = "dryRun", description = "Simulate deployment without modifying GCP resources")
    var dryRunOption: Boolean = false

    @get:Input
    @set:Option(option = "preview", description = "Preview deployment commands without modifying GCP resources")
    var previewOption: Boolean = false

    @TaskAction
    fun execute() {
        val ext = extension.get()
        val isDryRun = dryRunOption || previewOption || ext.dryRun.get()

        val projectId = ext.projectId.orNull?.ifBlank { null }
            ?: System.getenv("GCP_PROJECT_ID")?.ifBlank { null }
            ?: CliRunner.run("gcloud", "config", "get-value", "project", ignoreExitCode = true, printOutput = false).stdout
                .lines().firstOrNull()?.trim()?.ifBlank { null }
            ?: if (isDryRun) "dryrun-project-id" else throw GradleException("GCP Project ID is required. Specify via cloudRun.projectId or GCP_PROJECT_ID environment variable.")

        val region = ext.region.get()
        val serviceName = ext.serviceName.orNull ?: project.name
        val artifactRepo = ext.artifactRepo.get()

        val tag = tagOption?.ifBlank { null }
            ?: System.getenv("GITHUB_SHA")?.ifBlank { null }
            ?: resolveGitSha()
            ?: "latest"

        val imageUri = "$region-docker.pkg.dev/$projectId/$artifactRepo/$serviceName:$tag"

        val headerSuffix = if (isDryRun) " [DRY RUN]" else ""
        println("==========================================================")
        println("🚀 Deploying to Google Cloud Run (com.awakekt.cloudrun)$headerSuffix")
        println("   Project ID  : $projectId")
        println("   Region      : $region")
        println("   Service Name: $serviceName")
        println("   Image URI   : $imageUri")
        println("==========================================================")

        val buildArgs = listOf(
            "gcloud", "builds", "submit",
            "--project=$projectId",
            "--tag=$imageUri",
            project.rootDir.absolutePath
        )

        // 1. Submit to Cloud Build
        if (isDryRun) {
            println("[DRY RUN] ☁️  1. Would submit to Cloud Build:")
            println("   ${buildArgs.joinToString(" ")}")
        } else {
            println("☁️  1. Submitting to Cloud Build and pushing container...")
            CliRunner.run(*buildArgs.toTypedArray())
        }

        // 2. Deploy to Cloud Run
        val deployArgs = mutableListOf(
            "gcloud", "run", "deploy", serviceName,
            "--image=$imageUri",
            "--project=$projectId",
            "--region=$region",
            "--platform=managed"
        )

        if (ext.port.isPresent) {
            deployArgs.add("--port=${ext.port.get()}")
        }
        if (ext.minInstances.isPresent) {
            deployArgs.add("--min-instances=${ext.minInstances.get()}")
        }
        if (ext.maxInstances.isPresent) {
            deployArgs.add("--max-instances=${ext.maxInstances.get()}")
        }
        if (ext.memory.isPresent) {
            deployArgs.add("--memory=${ext.memory.get()}")
        }
        if (ext.cpu.isPresent) {
            deployArgs.add("--cpu=${ext.cpu.get()}")
        }
        if (ext.noCpuThrottling.get()) {
            deployArgs.add("--no-cpu-throttling")
        }
        if (ext.allowUnauthenticated.get()) {
            deployArgs.add("--allow-unauthenticated")
        }
        if (ext.noInvokerIamCheck.get()) {
            deployArgs.add("--no-invoker-iam-check")
        }

        val envMap = ext.envVars.get()
        if (envMap.isNotEmpty()) {
            val envString = envMap.entries.joinToString(",") { "${it.key}=${it.value}" }
            deployArgs.add("--set-env-vars=$envString")
        }

        val secretMap = ext.secrets.get()
        if (secretMap.isNotEmpty()) {
            val secretString = secretMap.entries.joinToString(",") { "${it.key}=${it.value}" }
            deployArgs.add("--set-secrets=$secretString")
        }

        deployArgs.add("--format=value(status.url)")

        if (isDryRun) {
            println()
            println("[DRY RUN] 🚢 2. Would deploy service to Cloud Run:")
            println("   ${deployArgs.joinToString(" ")}")
            println()
            println("==========================================================")
            println("🔎 Dry Run Complete! No changes were made to GCP.")
            println("   Planned Service URL: https://$serviceName-<hash>-$region.a.run.app")
            println("==========================================================")
            return
        }

        println("🚢 2. Deploying service to Cloud Run...")
        val deployResult = CliRunner.run(*deployArgs.toTypedArray())
        val serviceUrl = deployResult.stdout.lines().lastOrNull { it.startsWith("https://") } ?: deployResult.stdout.trim()

        println("==========================================================")
        println("🎉 Cloud Run Deployment Complete!")
        println("   Service URL: $serviceUrl")
        println("==========================================================")
    }

    private fun resolveGitSha(): String? {
        val result = CliRunner.run("git", "rev-parse", "HEAD", ignoreExitCode = true, printOutput = false)
        return if (result.exitCode == 0) result.stdout.trim() else null
    }
}
