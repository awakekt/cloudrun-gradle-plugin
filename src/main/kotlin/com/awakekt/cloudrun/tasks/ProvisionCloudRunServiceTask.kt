package com.awakekt.cloudrun.tasks

import com.awakekt.cloudrun.CloudRunExtension
import com.awakekt.cloudrun.internal.CliGcloud
import com.awakekt.cloudrun.internal.CliRunner
import com.awakekt.cloudrun.internal.Gcloud
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
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
abstract class ProvisionCloudRunServiceTask : DefaultTask() {

    @get:Internal
    abstract val extension: Property<CloudRunExtension>

    @get:Input
    @get:Optional
    @set:Option(option = "project", description = "GCP Project ID")
    var projectOption: String? = null

    @get:Input
    @set:Option(option = "dryRun", description = "Print the gcloud calls, with secret values masked, without changing GCP")
    var dryRunOption: Boolean = false

    @get:Input
    @set:Option(option = "preview", description = "Same as --dryRun")
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

        provisionCloudRunService(ext, projectId, isDryRun, CliGcloud, System::getenv, File(temporaryDir, "cleanup-policies.json"))
    }
}

internal const val SECRET_MASK = "********"

private val API_RUN = listOf("run.googleapis.com", "cloudbuild.googleapis.com", "artifactregistry.googleapis.com")

// Roles every deploy needs; cloudRun.extraDeployRoles adds the service's own.
private val DEPLOY_ROLES = listOf(
    "roles/run.admin",
    "roles/cloudbuild.builds.editor",
    "roles/serviceusage.serviceUsageConsumer",
    "roles/storage.admin",
)

/**
 * Makes a project ready for [CloudRunExtension]'s service: the APIs, each secret from `secret(...)` stored
 * from the environment variable of the same name, the runtime account's access to them, and the deploy
 * account's roles. Re-running changes nothing: a secret is created once, and a new version is added only
 * when the variable's value differs from the latest. [env] supplies the values, which reach gcloud on stdin
 * and never appear in [log]. [policyFile] is where the Artifact Registry cleanup policy is written for gcloud.
 */
internal fun provisionCloudRunService(
    ext: CloudRunExtension,
    projectId: String,
    dryRun: Boolean,
    gcloud: Gcloud,
    env: (String) -> String?,
    policyFile: File,
    log: (String) -> Unit = ::println,
) {
    val secrets = ext.secrets.get().map { (envKey, ref) -> envKey to ref.substringBefore(':') }
    val signsUrls = ext.signsUrls.get()
    val deployer = "serviceAccount:${ext.serviceAccountName.get()}@$projectId.iam.gserviceaccount.com"
    val prefix = if (dryRun) "[DRY RUN] " else ""

    fun change(args: List<String>, stdin: String? = null, note: String = "") {
        log("$prefix  ${args.joinToString(" ")}$note")
        if (!dryRun) gcloud.run(args, stdin, ignoreExitCode = false)
    }
    fun query(args: List<String>) = gcloud.run(args, null, ignoreExitCode = true)
    fun step(title: String) = log("$prefix== $title")

    val runtime = ext.runtimeServiceAccount.orNull?.ifBlank { null } ?: run {
        val number = if (dryRun) "<project-number>" else
            gcloud.run(listOf("gcloud", "projects", "describe", projectId, "--format=value(projectNumber)"), null, ignoreExitCode = false).stdout.trim()
        "$number-compute@developer.gserviceaccount.com"
    }

    log("$prefix== Provisioning $projectId for ${ext.serviceName.orNull ?: "the service"} (runtime account $runtime)")

    step("Enabling APIs")
    val apis = API_RUN + listOfNotNull("secretmanager.googleapis.com".takeIf { secrets.isNotEmpty() }) +
        listOfNotNull("iamcredentials.googleapis.com".takeIf { signsUrls })
    change(listOf("gcloud", "services", "enable") + apis + listOf("--project=$projectId", "--quiet"))

    val missing = mutableListOf<Pair<String, String>>()
    if (secrets.isNotEmpty()) step("Secrets")
    for ((envKey, secret) in secrets) {
        val project = "--project=$projectId"
        val value = env(envKey)?.takeIf { it.isNotBlank() }
        if (dryRun || query(listOf("gcloud", "secrets", "describe", secret, project)).exitCode != 0) {
            change(listOf("gcloud", "secrets", "create", secret, "--replication-policy=automatic", project, "--quiet"))
        }
        if (value != null) {
            val latest = if (dryRun) null else
                query(listOf("gcloud", "secrets", "versions", "access", "latest", "--secret=$secret", project)).takeIf { it.exitCode == 0 }?.stdout
            if (latest != null && latest.trim() == value.trim()) {
                log("  $secret already holds the value of \$$envKey")
            } else {
                change(listOf("gcloud", "secrets", "versions", "add", secret, "--data-file=-", project, "--quiet"), value, "   # stdin: $SECRET_MASK (from \$$envKey)")
            }
        } else if (dryRun) {
            log("$prefix  # \$$envKey is not set: $secret keeps its current versions, and needs one")
        } else if (query(listOf("gcloud", "secrets", "versions", "list", secret, "--filter=state=enabled", "--limit=1", "--format=value(name)", project)).stdout.isBlank()) {
            missing += envKey to secret
        }
        change(listOf("gcloud", "secrets", "add-iam-policy-binding", secret, "--member=serviceAccount:$runtime", "--role=roles/secretmanager.secretAccessor", project, "--quiet"))
    }

    if (signsUrls) {
        step("Runtime account signing")
        change(listOf("gcloud", "iam", "service-accounts", "add-iam-policy-binding", runtime, "--member=serviceAccount:$runtime", "--role=roles/iam.serviceAccountTokenCreator", "--project=$projectId", "--quiet"))
    }

    step("Deploy account access ($deployer)")
    for (role in DEPLOY_ROLES + ext.extraDeployRoles.get()) {
        change(listOf("gcloud", "projects", "add-iam-policy-binding", projectId, "--member=$deployer", "--role=$role", "--condition=None", "--quiet"))
    }
    // Deploying a service that runs as another account means acting as it.
    change(listOf("gcloud", "iam", "service-accounts", "add-iam-policy-binding", runtime, "--member=$deployer", "--role=roles/iam.serviceAccountUser", "--project=$projectId", "--quiet"))

    val keep = ext.keepImages.getOrElse(0)
    require(keep >= 0) { "cloudRun.keepImages must be 0 or more, not $keep." }
    if (keep > 0) {
        step("Artifact Registry cleanup")
        applyCleanupPolicy(ext.artifactRepo.get(), ext.region.get(), ext.serviceName.get(), keep, projectId, dryRun, gcloud, policyFile, log)
    }

    // Cloud Run starts no revision whose secret has no version, so say so here rather than at deploy.
    if (missing.isNotEmpty()) {
        val list = missing.joinToString("\n") { (envKey, secret) -> "  $secret: export $envKey and run provisionCloudRunService again" }
        throw GradleException("No enabled version for ${missing.size} secret(s), and no environment variable to store one from:\n$list")
    }
    log(if (dryRun) "[DRY RUN] No changes were made to GCP." else "Provisioned $projectId.")
}

/**
 * Keeps the newest [keep] images of [service] in [repo] and deletes older ones, through two cleanup policies named
 * after the service. Policies other services put on the repository stay: gcloud may replace the whole set, so the
 * existing ones are read first and written back with these two. Nothing is set when the repository already holds
 * exactly this. packageNamePrefixes matches by prefix, so a service named "api" also covers "api-worker".
 */
private fun applyCleanupPolicy(
    repo: String, region: String, service: String, keep: Int, projectId: String,
    dryRun: Boolean, gcloud: Gcloud, policyFile: File, log: (String) -> Unit,
) {
    val prefix = if (dryRun) "[DRY RUN] " else ""
    val ours = cleanupPolicies(service, keep)
    val where = listOf(repo, "--project=$projectId", "--location=$region")
    val set = listOf("gcloud", "artifacts", "repositories", "set-cleanup-policies") + where +
        listOf("--policy=${policyFile.path}", "--no-dry-run", "--quiet")

    if (dryRun) {
        log("$prefix  ${set.joinToString(" ")}")
        log("$prefix  # ${policyFile.name}, merged with the policies the repository already holds:")
        JsonOutput.prettyPrint(JsonOutput.toJson(ours)).lines().forEach { log("$prefix  $it") }
        return
    }

    val listed = gcloud.run(listOf("gcloud", "artifacts", "repositories", "list-cleanup-policies") + where, null, ignoreExitCode = false)
    val existing = (JsonSlurper().parseText(listed.stdout.ifBlank { "[]" }) as List<*>).filterIsInstance<Map<*, *>>()
    val ourNames = ours.map { it["name"] }.toSet()
    val others = existing.filter { it["name"] !in ourNames }
    val inert = "Dry run is enabled" in listed.stderr
    val current = existing.filter { it["name"] in ourNames }
    if (!inert && canonical(current) == canonical(ours)) {
        log("  $repo already keeps the newest $keep images of $service")
        return
    }
    policyFile.writeText(JsonOutput.toJson(others + ours))
    try {
        log("  ${set.joinToString(" ")}")
        gcloud.run(set, null, ignoreExitCode = false)
    } finally {
        policyFile.delete()
    }
}

// gcloud documents "Keep" and "any"; the canonical enum names are what its client recognizes and what it lists back.
private fun cleanupPolicies(service: String, keep: Int): List<Map<String, Any>> = listOf(
    linkedMapOf(
        "name" to "keep-newest-$service",
        "action" to mapOf("type" to "KEEP"),
        "mostRecentVersions" to linkedMapOf("packageNamePrefixes" to listOf(service), "keepCount" to keep),
    ),
    linkedMapOf(
        "name" to "delete-older-$service",
        "action" to mapOf("type" to "DELETE"),
        "condition" to linkedMapOf("tagState" to "ANY", "packageNamePrefixes" to listOf(service)),
    ),
)

// Order and number types differ between what is written and what gcloud lists back; compare the meaning.
private fun canonical(policies: List<*>): Any? = policies.map { norm(it) }.sortedBy { (it as Map<*, *>)["name"].toString() }

private fun norm(value: Any?): Any? = when (value) {
    is Map<*, *> -> value.entries.associate { it.key.toString() to norm(it.value) }.toSortedMap()
    is List<*> -> value.map { norm(it) }
    null -> null
    else -> value.toString()
}
