package com.awakekt.cloudrun

import org.gradle.testfixtures.ProjectBuilder
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
}
