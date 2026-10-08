package com.awakekt.cloudrun

import com.awakekt.cloudrun.tasks.DeployCloudRunTask
import com.awakekt.cloudrun.tasks.MapCustomDomainTask
import com.awakekt.cloudrun.tasks.ProvisionWorkloadIdentityTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.register

class CloudRunPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val extension = project.extensions.create<CloudRunExtension>("cloudRun")

        // Default project id from environment or gradle properties
        val defaultProject = System.getenv("GCP_PROJECT_ID")
            ?: project.findProperty("gcpProjectId") as? String
            ?: ""
        extension.projectId.convention(defaultProject)

        // Default service name from project name
        extension.serviceName.convention(project.name)

        // Default dry-run from gradle properties (-PdryRun, -Ppreview) or env DRY_RUN
        val isDryRun = project.hasProperty("dryRun") ||
            (project.findProperty("dryRun") as? String)?.toBoolean() == true ||
            project.hasProperty("preview") ||
            System.getenv("DRY_RUN")?.toBoolean() == true
        extension.dryRun.convention(isDryRun)

        // Register tasks
        project.tasks.register<ProvisionWorkloadIdentityTask>("provisionGcpWorkloadIdentity") {
            group = "cloud run"
            description = "Provisions GCP Workload Identity Federation, Service Account, and Artifact Registry."
            this.extension.set(extension)
        }

        val deployTask = project.tasks.register<DeployCloudRunTask>("deployCloudRun") {
            group = "cloud run"
            description = "Builds container via Cloud Build and deploys service to Cloud Run."
            this.extension.set(extension)
        }

        // Auto-wire to buildFatJar or shadowJar if present
        project.afterEvaluate {
            val buildJarTask = project.tasks.findByName("buildFatJar")
                ?: project.tasks.findByName("shadowJar")
                ?: project.tasks.findByName("bootJar")
                ?: project.tasks.findByName("jar")

            if (buildJarTask != null) {
                deployTask.configure {
                    dependsOn(buildJarTask)
                }
            }
        }

        project.tasks.register<MapCustomDomainTask>("mapCustomDomain") {
            group = "cloud run"
            description = "Creates a custom domain mapping for Cloud Run in supported regions."
            this.extension.set(extension)
        }
    }
}
