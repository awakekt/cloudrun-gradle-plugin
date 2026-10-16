package com.awakekt.cloudrun

import com.awakekt.cloudrun.internal.CliRunner
import com.awakekt.cloudrun.internal.Gcloud
import com.awakekt.cloudrun.internal.ProcessResult
import com.awakekt.cloudrun.tasks.cloudRunDeployArgs
import com.awakekt.cloudrun.tasks.provisionCloudRunService
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import java.io.File
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A gcloud that keeps Secret Manager state and records every call; nothing leaves the process. */
private class FakeGcloud : Gcloud {
    val calls = mutableListOf<List<String>>()
    val stdins = mutableListOf<String?>()
    val versions = mutableMapOf<String, MutableList<String>>()

    /** The repository's cleanup policies, as `list-cleanup-policies` prints them. */
    var policies: List<Any?> = emptyList()
    var policyDryRun = false
    val policyFiles = mutableListOf<String>()

    override fun run(args: List<String>, stdin: String?, ignoreExitCode: Boolean): ProcessResult {
        calls += args
        stdins += stdin
        val secret = args.getOrNull(3)
        return when (args.drop(1).take(3).joinToString(" ")) {
            "secrets describe $secret" -> ProcessResult(if (secret in versions) 0 else 1, "", "")
            "secrets create $secret" -> { versions[secret!!] = mutableListOf(); ok() }
            "secrets versions add" -> { versions.getValue(args[4]).add(stdin!!); ok() }
            "secrets versions access" -> versions[args[5].removePrefix("--secret=")]?.lastOrNull()
                ?.let { ProcessResult(0, it, "") } ?: ProcessResult(1, "", "no version")
            "secrets versions list" -> ProcessResult(0, if (versions[args[4]].orEmpty().isEmpty()) "" else "1", "")
            "artifacts repositories list-cleanup-policies" ->
                ProcessResult(0, JsonOutput.toJson(policies), if (policyDryRun) "Dry run is enabled." else "Dry run is disabled.")
            "artifacts repositories set-cleanup-policies" -> {
                // Replaces the whole set, which is the worst case for a repository other services share.
                val text = File(args.single { it.startsWith("--policy=") }.removePrefix("--policy=")).readText()
                policyFiles += text
                policies = JsonSlurper().parseText(text) as List<*>
                policyDryRun = "--dry-run" in args
                ok()
            }
            "projects describe $secret" -> ProcessResult(0, "123456", "")
            else -> ok()
        }
    }

    private fun ok() = ProcessResult(0, "", "")

    fun line(vararg parts: String) = calls.firstOrNull { c -> parts.all { it in c.joinToString(" ") } }
    fun count(prefix: String) = calls.count { it.joinToString(" ").startsWith(prefix) }
}

class ProvisionCloudRunServiceTest {

    private val policyFile = File.createTempFile("cleanup-policies", ".json").also { it.deleteOnExit() }

    private fun ext(): CloudRunExtension {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply("com.awakekt.cloudrun")
        return project.extensions.getByType(CloudRunExtension::class.java).apply {
            serviceName.set("svc")
            runtimeServiceAccount.set("runtime@p.iam.gserviceaccount.com")
            secret("API_KEY", "api-key:latest")
            secret("SIGNING_KEY", "signing-key:latest")
            signsUrls.set(true)
            extraDeployRoles.add("roles/datastore.indexAdmin")
        }
    }

    private val values = mapOf("API_KEY" to "api-secret-value-111", "SIGNING_KEY" to "line one\nline two")

    private fun run(
        gcloud: FakeGcloud,
        dryRun: Boolean = false,
        env: Map<String, String> = values,
        ext: CloudRunExtension = ext(),
    ): String {
        val log = StringBuilder()
        provisionCloudRunService(ext, "p", dryRun, gcloud, env::get, policyFile) { log.appendLine(it) }
        return log.toString()
    }

    @Test
    fun `a real run issues exactly these gcloud calls`() {
        val gcloud = FakeGcloud()
        run(gcloud)

        val expected = listOf(
            "gcloud services enable run.googleapis.com cloudbuild.googleapis.com artifactregistry.googleapis.com secretmanager.googleapis.com iamcredentials.googleapis.com --project=p --quiet",
            "gcloud secrets describe api-key --project=p",
            "gcloud secrets create api-key --replication-policy=automatic --project=p --quiet",
            "gcloud secrets versions access latest --secret=api-key --project=p",
            "gcloud secrets versions add api-key --data-file=- --project=p --quiet",
            "gcloud secrets add-iam-policy-binding api-key --member=serviceAccount:runtime@p.iam.gserviceaccount.com --role=roles/secretmanager.secretAccessor --project=p --quiet",
            "gcloud secrets describe signing-key --project=p",
            "gcloud secrets create signing-key --replication-policy=automatic --project=p --quiet",
            "gcloud secrets versions access latest --secret=signing-key --project=p",
            "gcloud secrets versions add signing-key --data-file=- --project=p --quiet",
            "gcloud secrets add-iam-policy-binding signing-key --member=serviceAccount:runtime@p.iam.gserviceaccount.com --role=roles/secretmanager.secretAccessor --project=p --quiet",
            "gcloud iam service-accounts add-iam-policy-binding runtime@p.iam.gserviceaccount.com --member=serviceAccount:runtime@p.iam.gserviceaccount.com --role=roles/iam.serviceAccountTokenCreator --project=p --quiet",
            "gcloud projects add-iam-policy-binding p --member=serviceAccount:github-actions-deployer@p.iam.gserviceaccount.com --role=roles/run.admin --condition=None --quiet",
            "gcloud projects add-iam-policy-binding p --member=serviceAccount:github-actions-deployer@p.iam.gserviceaccount.com --role=roles/cloudbuild.builds.editor --condition=None --quiet",
            "gcloud projects add-iam-policy-binding p --member=serviceAccount:github-actions-deployer@p.iam.gserviceaccount.com --role=roles/serviceusage.serviceUsageConsumer --condition=None --quiet",
            "gcloud projects add-iam-policy-binding p --member=serviceAccount:github-actions-deployer@p.iam.gserviceaccount.com --role=roles/storage.admin --condition=None --quiet",
            "gcloud projects add-iam-policy-binding p --member=serviceAccount:github-actions-deployer@p.iam.gserviceaccount.com --role=roles/datastore.indexAdmin --condition=None --quiet",
            "gcloud iam service-accounts add-iam-policy-binding runtime@p.iam.gserviceaccount.com --member=serviceAccount:github-actions-deployer@p.iam.gserviceaccount.com --role=roles/iam.serviceAccountUser --project=p --quiet",
            "gcloud artifacts repositories list-cleanup-policies cloudrun --project=p --location=asia-southeast1",
            "gcloud artifacts repositories set-cleanup-policies cloudrun --project=p --location=asia-southeast1 --policy=${policyFile.path} --no-dry-run --quiet",
        )
        assertEquals(expected, gcloud.calls.map { it.joinToString(" ") })
        // The values travel on stdin, in the order they were stored, never as arguments.
        assertEquals(listOf("api-secret-value-111", "line one\nline two"), gcloud.stdins.filterNotNull())
        assertFalse(gcloud.calls.any { c -> c.any { "api-secret-value-111" in it } })
    }

    @Test
    fun `signing grant and IAM Credentials API are left out unless signsUrls`() {
        val gcloud = FakeGcloud()
        run(gcloud, ext = ext().apply { signsUrls.set(false) })

        assertFalse(gcloud.calls.any { "serviceAccountTokenCreator" in it.joinToString(" ") })
        assertFalse(gcloud.calls.any { "iamcredentials.googleapis.com" in it })
    }

    @Test
    fun `a second run creates and stores nothing`() {
        val gcloud = FakeGcloud()
        run(gcloud)
        assertEquals(2, gcloud.count("gcloud secrets create"))
        assertEquals(2, gcloud.count("gcloud secrets versions add"))

        gcloud.calls.clear()
        run(gcloud)

        assertEquals(0, gcloud.count("gcloud secrets create"))
        assertEquals(0, gcloud.count("gcloud secrets versions add"))
        assertEquals(listOf(1, 1), gcloud.versions.values.map { it.size })
    }

    @Test
    fun `a changed value adds exactly one new version`() {
        val gcloud = FakeGcloud()
        run(gcloud)
        gcloud.calls.clear()

        run(gcloud, env = values + ("API_KEY" to "rotated-value"))

        assertEquals(1, gcloud.count("gcloud secrets versions add"))
        assertEquals(listOf("api-secret-value-111", "rotated-value"), gcloud.versions.getValue("api-key").toList())
    }

    @Test
    fun `dry run prints the calls, masks values and changes nothing`() {
        val gcloud = FakeGcloud()
        val out = run(gcloud, dryRun = true)

        assertTrue(gcloud.calls.isEmpty(), "a dry run reached gcloud: ${gcloud.calls}")
        assertFalse("api-secret-value-111" in out)
        assertFalse("line one" in out)
        assertTrue("gcloud secrets versions add api-key --data-file=- --project=p --quiet   # stdin: ******** (from \$API_KEY)" in out)
        assertTrue("--role=roles/datastore.indexAdmin" in out)
        assertTrue("--role=roles/iam.serviceAccountTokenCreator" in out)
        assertTrue(out.lines().filter { it.contains("gcloud") }.all { it.startsWith("[DRY RUN] ") })
    }

    @Test
    fun `positive control - a real run does send the value that the dry run hid`() {
        val gcloud = FakeGcloud()
        val out = run(gcloud)

        assertTrue("api-secret-value-111" in gcloud.stdins.filterNotNull().joinToString())
        // The real run's own log never shows it either.
        assertFalse("api-secret-value-111" in out)
    }

    @Test
    fun `dry run without a runtime account shows the Compute Engine default`() {
        val out = run(FakeGcloud(), dryRun = true, ext = ext().apply { runtimeServiceAccount.set("") })

        assertTrue("<project-number>-compute@developer.gserviceaccount.com" in out)
    }

    @Test
    fun `a secret with no value and no version fails naming the variable`() {
        val gcloud = FakeGcloud()
        val error = assertFailsWith<GradleException> { run(gcloud, env = mapOf("SIGNING_KEY" to "v")) }

        assertTrue("api-key: export API_KEY and run provisionCloudRunService again" in error.message!!, error.message)
        assertFalse("signing-key" in error.message!!)
        // The rest was provisioned first, so one run reports every gap.
        assertTrue(gcloud.line("--role=roles/storage.admin") != null)
    }

    @Test
    fun `positive control - the same missing variable passes once the secret has a version`() {
        val gcloud = FakeGcloud()
        run(gcloud)

        run(gcloud, env = mapOf("SIGNING_KEY" to "line one\nline two"))
        assertEquals(1, gcloud.versions.getValue("api-key").size)
    }

    @Test
    fun `positive control - a blank variable counts as not set`() {
        assertFailsWith<GradleException> { run(FakeGcloud(), env = values + ("API_KEY" to "  ")) }
    }

    @Test
    fun `the registered task dry-runs without touching GCP`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply("com.awakekt.cloudrun")
        val ext = project.extensions.getByType(CloudRunExtension::class.java)
        ext.projectId.set("my-test-project")
        ext.secret("API_KEY", "api-key:latest")
        val task = project.tasks.getByName("provisionCloudRunService") as com.awakekt.cloudrun.tasks.ProvisionCloudRunServiceTask
        task.dryRunOption = true
        task.execute()
    }

    @Test
    fun `CliRunner hands stdin to the command`() {
        if (System.getProperty("os.name").lowercase().contains("windows")) return
        assertEquals("from stdin", CliRunner.run("cat", stdin = "from stdin", printOutput = false).stdout)
    }

    private fun deployArgs(configure: CloudRunExtension.() -> Unit = {}): List<String> {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply("com.awakekt.cloudrun")
        val ext = project.extensions.getByType(CloudRunExtension::class.java).apply(configure)
        return cloudRunDeployArgs(ext, "svc", "image:tag", "p", "asia-east1", null)
    }

    @Test
    fun `concurrency and timeout are passed only when set`() {
        val unset = deployArgs()
        assertFalse(unset.any { it.startsWith("--concurrency") || it.startsWith("--timeout") })

        val set = deployArgs {
            concurrency.set(40)
            timeout.set(Duration.ofMinutes(10))
        }
        assertTrue("--concurrency=40" in set)
        assertTrue("--timeout=600s" in set)
        assertEquals(
            listOf("--cpu=1", "--concurrency=40", "--timeout=600s", "--no-cpu-throttling"),
            set.subList(set.indexOf("--cpu=1"), set.indexOf("--cpu=1") + 4),
        )
    }

    @Test
    fun `concurrency and timeout reject values Cloud Run would refuse`() {
        assertFailsWith<IllegalArgumentException> { deployArgs { concurrency.set(0) } }
        assertFailsWith<IllegalArgumentException> { deployArgs { timeout.set(Duration.ZERO) } }
    }

    private val twoPolicies = "[" +
        """{"name":"keep-newest-svc","action":{"type":"KEEP"},"mostRecentVersions":{"packageNamePrefixes":["svc"],"keepCount":3}},""" +
        """{"name":"delete-older-svc","action":{"type":"DELETE"},"condition":{"tagState":"ANY","packageNamePrefixes":["svc"]}}""" +
        "]"

    private fun artifactCalls(gcloud: FakeGcloud) = gcloud.calls.filter { it.getOrNull(1) == "artifacts" }.map { it.joinToString(" ") }

    @Test
    fun `cleanup keeps the newest 3 images of the service by default - the policy file is pinned`() {
        val gcloud = FakeGcloud()
        run(gcloud)

        assertEquals(1, gcloud.policyFiles.size)
        assertEquals(twoPolicies, gcloud.policyFiles.single())
        assertFalse(policyFile.exists(), "the policy file is removed after the call")
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun `positive control - keepImages sets exactly N in the keep policy and the delete policy covers the same package`() {
        for (n in listOf(1, 5, 12)) {
            val gcloud = FakeGcloud()
            run(gcloud, ext = ext().apply { keepImages.set(n) })

            val policies = JsonSlurper().parseText(gcloud.policyFiles.single()) as List<Map<*, *>>
            val keeps = policies.filter { (it["action"] as Map<*, *>)["type"] == "KEEP" }
            val deletes = policies.filter { (it["action"] as Map<*, *>)["type"] == "DELETE" }
            assertEquals(1, keeps.size)
            assertEquals(1, deletes.size)
            val recent = keeps.single()["mostRecentVersions"] as Map<*, *>
            assertEquals(n, recent["keepCount"])
            assertEquals(listOf("svc"), recent["packageNamePrefixes"])
            assertEquals(mapOf("tagState" to "ANY", "packageNamePrefixes" to listOf("svc")), deletes.single()["condition"])
        }
    }

    @Test
    fun `keepImages 0 or no value sets no policy and does not even read the repository`() {
        val zero = FakeGcloud()
        run(zero, ext = ext().apply { keepImages.set(0) })
        assertEquals(emptyList(), artifactCalls(zero))

        val unset = FakeGcloud()
        run(unset, ext = ext().apply { keepImages.unsetConvention() })
        assertEquals(emptyList(), artifactCalls(unset))
        assertTrue(unset.policyFiles.isEmpty())

        // Control: the same extension with the default does set one.
        assertEquals(2, artifactCalls(FakeGcloud().also { run(it) }).size)
    }

    @Test
    fun `a negative keepImages is refused`() {
        assertFailsWith<IllegalArgumentException> { run(FakeGcloud(), ext = ext().apply { keepImages.set(-1) }) }
    }

    @Test
    fun `a second cleanup changes nothing, a new count changes only the count`() {
        val gcloud = FakeGcloud()
        run(gcloud)
        gcloud.calls.clear()

        run(gcloud)
        assertEquals(1, artifactCalls(gcloud).size, "only the read: ${artifactCalls(gcloud)}")
        assertEquals(1, gcloud.policyFiles.size)

        run(gcloud, ext = ext().apply { keepImages.set(4) })
        assertEquals(2, gcloud.policyFiles.size)
        assertEquals(twoPolicies.replace("\"keepCount\":3", "\"keepCount\":4"), gcloud.policyFiles.last())
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun `cleanup keeps the policies other services put on the repository`() {
        val gcloud = FakeGcloud()
        val other = mapOf("name" to "keep-newest-worker", "action" to mapOf("type" to "KEEP"), "mostRecentVersions" to mapOf("keepCount" to 7, "packageNamePrefixes" to listOf("worker")))
        gcloud.policies = listOf(other)

        run(gcloud)

        val written = JsonSlurper().parseText(gcloud.policyFiles.single()) as List<Map<*, *>>
        assertEquals(listOf("keep-newest-worker", "keep-newest-svc", "delete-older-svc"), written.map { it["name"] })
        assertEquals(other, written.first())
    }

    @Test
    fun `a repository in dry-run mode is switched back to deleting even when the policies match`() {
        val gcloud = FakeGcloud()
        run(gcloud)
        gcloud.policyDryRun = true

        run(gcloud)

        assertEquals(2, gcloud.policyFiles.size)
        assertFalse(gcloud.policyDryRun)
    }

    @Test
    fun `dry run prints the cleanup command and policy without reaching gcloud`() {
        val gcloud = FakeGcloud()
        val out = run(gcloud, dryRun = true)

        assertTrue(gcloud.calls.isEmpty())
        assertTrue("[DRY RUN]   gcloud artifacts repositories set-cleanup-policies cloudrun --project=p --location=asia-southeast1 --policy=${policyFile.path} --no-dry-run --quiet" in out)
        assertEquals(twoPolicies, out.lines().filter { it.startsWith("[DRY RUN]   ") }.map { it.removePrefix("[DRY RUN]   ") }
            .dropWhile { it != "[" }.takeWhile { it != "" }.joinToString("").let { JsonOutput.toJson(JsonSlurper().parseText(it)) })
        assertTrue(out.lines().filter { it.isNotBlank() }.all { it.startsWith("[DRY RUN] ") })
    }

    @Test
    fun `cpuBoost adds --cpu-boost only when true`() {
        val unset = deployArgs()
        assertFalse("--cpu-boost" in unset)
        assertFalse(unset.any { it.contains("cpu-boost") })

        assertFalse(deployArgs { cpuBoost.set(false) }.any { it.contains("cpu-boost") })

        val on = deployArgs { cpuBoost.set(true) }
        assertEquals(
            listOf("--cpu=1", "--cpu-boost", "--no-cpu-throttling"),
            on.subList(on.indexOf("--cpu=1"), on.indexOf("--cpu=1") + 3),
        )
    }
}
