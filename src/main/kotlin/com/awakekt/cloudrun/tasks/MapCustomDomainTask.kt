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

@DisableCachingByDefault(because = "Communicates with remote Google Cloud APIs")
abstract class MapCustomDomainTask : DefaultTask() {

    @get:Internal
    abstract val extension: Property<CloudRunExtension>

    @get:Input
    @get:Optional
    @set:Option(option = "domain", description = "Custom domain to map (e.g. api.example.com)")
    var domainOption: String? = null

    @get:Input
    @set:Option(option = "dryRun", description = "Simulate domain mapping without modifying GCP resources")
    var dryRunOption: Boolean = false

    @get:Input
    @set:Option(option = "preview", description = "Preview domain mapping command without modifying GCP resources")
    var previewOption: Boolean = false

    @TaskAction
    fun execute() {
        val ext = extension.get()
        val isDryRun = dryRunOption || previewOption || ext.dryRun.get()

        val domain = domainOption?.ifBlank { null }
            ?: if (isDryRun) "api.example.com" else throw GradleException("Custom domain is required. Specify via --domain=<your-domain.com>")

        val projectId = ext.projectId.orNull?.ifBlank { null }
            ?: System.getenv("GCP_PROJECT_ID")?.ifBlank { null }
            ?: CliRunner.run("gcloud", "config", "get-value", "project", ignoreExitCode = true, printOutput = false).stdout
                .lines().firstOrNull()?.trim()?.ifBlank { null }
            ?: if (isDryRun) "dryrun-project-id" else throw GradleException("GCP Project ID is required.")

        val region = ext.region.get()
        val serviceName = ext.serviceName.orNull ?: project.name

        val supportedRegions = listOf(
            "asia-southeast1", "asia-east1", "asia-northeast1",
            "us-central1", "us-east1", "us-east4", "us-west1",
            "europe-north1", "europe-west1", "europe-west4"
        )

        if (!supportedRegions.contains(region)) {
            println("⚠️ Warning: Cloud Run domain mapping is currently not supported in '$region'.")
            println("   Recommended regions in Asia: asia-southeast1 (Singapore), asia-east1 (Taiwan).")
        }

        val headerSuffix = if (isDryRun) " [DRY RUN]" else ""
        println("==========================================================")
        println("🌐 Mapping Custom Domain (com.awakekt.cloudrun)$headerSuffix")
        println("   Service: $serviceName")
        println("   Domain : $domain")
        println("   Region : $region")
        println("==========================================================")

        val mappingArgs = listOf(
            "gcloud", "beta", "run", "domain-mappings", "create",
            "--service=$serviceName",
            "--domain=$domain",
            "--region=$region",
            "--project=$projectId"
        )

        if (isDryRun) {
            println("[DRY RUN] Would execute:")
            println("   ${mappingArgs.joinToString(" ")}")
            println()
            println("==========================================================")
            println("🔎 Dry Run Complete! No changes were made to GCP.")
            println("==========================================================")
            return
        }

        CliRunner.run(*mappingArgs.toTypedArray())

        println("==========================================================")
        println("✅ Domain Mapping Created!")
        println("   Update your DNS with the CNAME record shown above.")
        println("==========================================================")
    }
}
