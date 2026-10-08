package com.awakekt.cloudrun.internal

import org.gradle.api.GradleException
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

object CliRunner {

    fun run(
        vararg args: String,
        workingDir: File? = null,
        ignoreExitCode: Boolean = false,
        printOutput: Boolean = true
    ): ProcessResult {
        val isWindows = System.getProperty("os.name").lowercase().contains("windows")
        val commandList = if (isWindows) {
            listOf("cmd.exe", "/c") + args
        } else {
            args.toList()
        }

        val pb = ProcessBuilder(commandList)
        if (workingDir != null) pb.directory(workingDir)

        val process = pb.start()
        val stdout = StringBuilder()
        val stderr = StringBuilder()

        val outReader = BufferedReader(InputStreamReader(process.inputStream))
        val errReader = BufferedReader(InputStreamReader(process.errorStream))

        var line: String?
        while (outReader.readLine().also { line = it } != null) {
            if (printOutput) println(line)
            stdout.appendLine(line)
        }
        while (errReader.readLine().also { line = it } != null) {
            if (printOutput) System.err.println(line)
            stderr.appendLine(line)
        }

        val exitCode = process.waitFor()
        if (exitCode != 0 && !ignoreExitCode) {
            val errorMsg = stderr.toString().ifBlank { stdout.toString() }
            throw GradleException("Command failed with exit code $exitCode:\n${commandList.joinToString(" ")}\n$errorMsg")
        }
        return ProcessResult(exitCode, stdout.toString().trim(), stderr.toString().trim())
    }

    fun hasCommand(command: String): Boolean {
        return try {
            val result = run(command, "--version", ignoreExitCode = true, printOutput = false)
            result.exitCode == 0
        } catch (_: Exception) {
            false
        }
    }
}

data class ProcessResult(val exitCode: Int, val stdout: String, val stderr: String)
