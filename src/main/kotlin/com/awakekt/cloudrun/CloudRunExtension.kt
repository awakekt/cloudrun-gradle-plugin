package com.awakekt.cloudrun

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import javax.inject.Inject

abstract class CloudRunExtension @Inject constructor(objects: ObjectFactory) {

    val projectId: Property<String> = objects.property(String::class.java)
    val region: Property<String> = objects.property(String::class.java).convention("asia-southeast1")
    val serviceName: Property<String> = objects.property(String::class.java)
    val artifactRepo: Property<String> = objects.property(String::class.java).convention("cloudrun")
    val port: Property<Int> = objects.property(Int::class.java).convention(8080)
    val minInstances: Property<Int> = objects.property(Int::class.java).convention(1)
    val maxInstances: Property<Int> = objects.property(Int::class.java).convention(3)
    val memory: Property<String> = objects.property(String::class.java).convention("1Gi")
    val cpu: Property<String> = objects.property(String::class.java).convention("1")
    val noCpuThrottling: Property<Boolean> = objects.property(Boolean::class.java).convention(true)
    val allowUnauthenticated: Property<Boolean> = objects.property(Boolean::class.java).convention(true)
    val noInvokerIamCheck: Property<Boolean> = objects.property(Boolean::class.java).convention(true)
    val envVars: MapProperty<String, String> = objects.mapProperty(String::class.java, String::class.java)
    val secrets: MapProperty<String, String> = objects.mapProperty(String::class.java, String::class.java)
    val jarFile: RegularFileProperty = objects.fileProperty()
    val dryRun: Property<Boolean> = objects.property(Boolean::class.java).convention(false)

    // Folder uploaded to Cloud Build; it must hold the Dockerfile. Defaults to the project's own directory.
    val sourceDir: DirectoryProperty = objects.directoryProperty()

    // Identity the service runs as (--service-account); Cloud Run's default is the Compute Engine account.
    val runtimeServiceAccount: Property<String> = objects.property(String::class.java)

    // Workload Identity Federation settings
    val githubRepo: Property<String> = objects.property(String::class.java)
    val serviceAccountName: Property<String> = objects.property(String::class.java).convention("github-actions-deployer")
    val workloadIdentityPool: Property<String> = objects.property(String::class.java).convention("github-pool")
    val workloadIdentityProvider: Property<String> = objects.property(String::class.java).convention("github-provider")
    val outputFile: Property<String> = objects.property(String::class.java).convention("gcp-workload-identity.env")

    fun env(key: String, value: String) {
        envVars.put(key, value)
    }

    fun secret(envKey: String, secretRef: String) {
        secrets.put(envKey, secretRef)
    }
}
