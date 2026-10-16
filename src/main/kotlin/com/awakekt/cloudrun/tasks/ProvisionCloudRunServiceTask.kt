package com.awakekt.cloudrun.tasks

import com.awakekt.cloudrun.CloudRunExtension
import com.awakekt.cloudrun.internal.CliGcloud
import com.awakekt.cloudrun.internal.CliRunner
import com.awakekt.cloudrun.internal.Gcloud
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

        provisionCloudRunService(ext, projectId, isDryRun, CliGcloud, System::getenv)
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
 * and never appear in [log].
 */
internal fun provisionCloudRunService(
    ext: CloudRunExtension,
    projectId: String,
    dryRun: Boolean,
    gcloud: Gcloud,
    env: (String) -> String?,
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

    // Cloud Run starts no revision whose secret has no version, so say so here rather than at deploy.
    if (missing.isNotEmpty()) {
        val list = missing.joinToString("\n") { (envKey, secret) -> "  $secret: export $envKey and run provisionCloudRunService again" }
        throw GradleException("No enabled version for ${missing.size} secret(s), and no environment variable to store one from:\n$list")
    }
    log(if (dryRun) "[DRY RUN] No changes were made to GCP." else "Provisioned $projectId.")
}
