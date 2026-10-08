package com.awakekt.cloudrun

import com.awakekt.cloudrun.internal.CliRunner
import com.awakekt.cloudrun.internal.ProcessResult
import com.awakekt.cloudrun.tasks.cloudRunDeployArgs
import com.awakekt.cloudrun.tasks.envVarsYaml
import org.gradle.testfixtures.ProjectBuilder
import java.io.File
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CloudRunPluginTest {

    @Test
    fun `plugin registers extension with defaults`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply("com.awakekt.cloudrun")

        val ext = project.extensions.findByType(CloudRunExtension::class.java)
        assertNotNull(ext)
        assertEquals("asia-southeast1", ext.region.get())
        assertEquals("cloudrun", ext.artifactRepo.get())
        assertEquals(8080, ext.port.get())
        assertEquals(1, ext.minInstances.get())
        assertEquals(3, ext.maxInstances.get())
        assertEquals("1Gi", ext.memory.get())
        assertEquals("1", ext.cpu.get())
        assertTrue(ext.noCpuThrottling.get())
        assertTrue(ext.allowUnauthenticated.get())
        assertTrue(ext.noInvokerIamCheck.get())
        assertFalse(ext.dryRun.get())
        assertEquals("github-actions-deployer", ext.serviceAccountName.get())
        assertEquals("github-pool", ext.workloadIdentityPool.get())
        assertEquals("github-provider", ext.workloadIdentityProvider.get())
    }

    @Test
    fun `plugin registers all tasks`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply("com.awakekt.cloudrun")

        assertNotNull(project.tasks.findByName("provisionGcpWorkloadIdentity"))
        assertNotNull(project.tasks.findByName("deployCloudRun"))
        assertNotNull(project.tasks.findByName("mapCustomDomain"))
    }

    @Test
    fun `dryRun can be enabled via extension DSL`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply("com.awakekt.cloudrun")

        val ext = project.extensions.getByType(CloudRunExtension::class.java)
        ext.dryRun.set(true)
        assertTrue(ext.dryRun.get())
    }

    @Test
    fun `deployCloudRun dry-run executes cleanly without mutating GCP`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply("com.awakekt.cloudrun")

        val ext = project.extensions.getByType(CloudRunExtension::class.java)
        ext.projectId.set("my-test-project")
        ext.serviceName.set("my-test-service")
        ext.dryRun.set(true)

        val task = project.tasks.getByName("deployCloudRun") as com.awakekt.cloudrun.tasks.DeployCloudRunTask
        task.execute()
    }

    @Test
    fun `provisionGcpWorkloadIdentity dry-run executes cleanly without mutating GCP`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply("com.awakekt.cloudrun")

        val ext = project.extensions.getByType(CloudRunExtension::class.java)
        ext.projectId.set("my-test-project")
        ext.githubRepo.set("test-org/test-repo")
        ext.dryRun.set(true)

        val task = project.tasks.getByName("provisionGcpWorkloadIdentity") as com.awakekt.cloudrun.tasks.ProvisionWorkloadIdentityTask
        task.execute()
    }

    @Test
    fun `mapCustomDomain dry-run executes cleanly without mutating GCP`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply("com.awakekt.cloudrun")

        val ext = project.extensions.getByType(CloudRunExtension::class.java)
        ext.projectId.set("my-test-project")
        ext.serviceName.set("my-test-service")
        ext.dryRun.set(true)

        val task = project.tasks.getByName("mapCustomDomain") as com.awakekt.cloudrun.tasks.MapCustomDomainTask
        task.domainOption = "api.test.com"
        task.execute()
    }

    @Test
    fun `sourceDir defaults to the applying project's directory, not the root`() {
        val root = ProjectBuilder.builder().build()
        val service = ProjectBuilder.builder().withName("service").withParent(root).build()
        service.plugins.apply("com.awakekt.cloudrun")

        val ext = service.extensions.getByType(CloudRunExtension::class.java)
        assertEquals(service.projectDir, ext.sourceDir.get().asFile)
    }

    @Test
    fun `deploy args carry the runtime service account and keep env values off the command line`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply("com.awakekt.cloudrun")

        val ext = project.extensions.getByType(CloudRunExtension::class.java)
        ext.runtimeServiceAccount.set("runtime@my-project.iam.gserviceaccount.com")
        ext.env("OWNERS", "first,second")
        ext.secret("API_KEY", "api-key:latest")

        val envFile = File(project.layout.buildDirectory.get().asFile, "env-vars.yaml")
        val args = cloudRunDeployArgs(ext, "svc", "image:tag", "my-project", "asia-southeast1", envFile)

        assertTrue("--service-account=runtime@my-project.iam.gserviceaccount.com" in args)
        assertTrue("--env-vars-file=${envFile.absolutePath}" in args)
        assertTrue("--set-secrets=API_KEY=api-key:latest" in args)
        assertFalse(args.any { "first,second" in it })
    }

    @Test
    fun `deploy args omit the service account and env file when unset`() {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply("com.awakekt.cloudrun")

        val ext = project.extensions.getByType(CloudRunExtension::class.java)
        val args = cloudRunDeployArgs(ext, "svc", "image:tag", "my-project", "asia-southeast1", null)

        assertFalse(args.any { it.startsWith("--service-account") })
        assertFalse(args.any { it.startsWith("--env-vars-file") })
    }

    @Test
    fun `env vars file quotes every value and escapes what YAML would read differently`() {
        val yaml = envVarsYaml(
            linkedMapOf(
                "OWNERS" to "first,second",
                "PORT" to "8080",
                "QUOTED" to "say \"hi\" \\ bye",
                "LINES" to "one\ntwo",
            ),
        )

        assertEquals(
            "\"OWNERS\": \"first,second\"\n" +
                "\"PORT\": \"8080\"\n" +
                "\"QUOTED\": \"say \\\"hi\\\" \\\\ bye\"\n" +
                "\"LINES\": \"one\\ntwo\"\n",
            yaml,
        )
    }

    @Test
    fun `CliRunner finishes when a command writes far more to stderr than a pipe holds`() {
        val program = File.createTempFile("Noisy", ".java").apply {
            deleteOnExit()
            writeText(
                """
                public class Noisy {
                    public static void main(String[] args) {
                        for (int i = 0; i < 20000; i++) System.err.println("progress " + i + " ..................................................");
                        System.out.println("done");
                    }
                }
                """.trimIndent(),
            )
        }
        val java = ProcessHandle.current().info().command().get()

        var result: ProcessResult? = null
        val runner = thread { result = CliRunner.run(java, program.absolutePath, printOutput = false) }
        runner.join(60_000)

        assertFalse(runner.isAlive, "CliRunner hung reading the command's output")
        assertEquals("done", result?.stdout)
        assertTrue(result!!.stderr.lines().size >= 20000)
    }
}
