package com.awakekt.cloudrun.internal

/** Runs one gcloud command. Tests replace it with a fake, so no test reaches Google Cloud. */
internal interface Gcloud {
    /** [stdin] carries a value that must stay off the command line. */
    fun run(args: List<String>, stdin: String?, ignoreExitCode: Boolean): ProcessResult
}

internal object CliGcloud : Gcloud {
    override fun run(args: List<String>, stdin: String?, ignoreExitCode: Boolean): ProcessResult =
        CliRunner.run(*args.toTypedArray(), stdin = stdin, ignoreExitCode = ignoreExitCode, printOutput = false)
}
