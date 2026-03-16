/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 *
 * This file is part of IntelliJ-Plugins by ghostflyby
 *
 * IntelliJ-Plugins by ghostflyby is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3.0 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, see
 * <https://www.gnu.org/licenses/>.
 */

package dev.ghostflyby.mill.command

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.ProcessOutputType
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import dev.ghostflyby.mill.MillConstants
import dev.ghostflyby.mill.MillImportDebugLogger
import dev.ghostflyby.mill.settings.MillExecutableConfigurationUtil
import dev.ghostflyby.mill.settings.MillExecutableSource
import dev.ghostflyby.mill.settings.MillExecutionSettings
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal data class MillCommandResult(
    val projectRoot: Path,
    val arguments: List<String>,
    val commandLineString: String,
    val stdout: String,
    val stderr: String,
    val exitCode: Int?,
    val startupFailed: Boolean,
    val timedOut: Boolean,
) {
    val invocation: String
        get() = arguments.joinToString(" ")

    val isSuccess: Boolean
        get() = !startupFailed && !timedOut && exitCode == 0

    val failureDetails: String
        get() = stderr.ifBlank { stdout }.trim().ifBlank {
            when {
                timedOut -> "The Mill process timed out."
                startupFailed -> "The Mill process could not be started."
                else -> ""
            }
        }

    fun reportFailure(
        taskId: ExternalSystemTaskId,
        listener: ExternalSystemTaskNotificationListener,
        failureContext: String,
        reportFailures: Boolean = true,
    ) {
        if (!reportFailures || isSuccess) {
            return
        }

        val output = if (startupFailed) {
            "Mill $failureContext skipped because the Mill process could not be started for `$invocation`.\n"
        } else if (timedOut) {
            "Mill $failureContext skipped because `$invocation` timed out.\n"
        } else {
            buildString {
                append("Mill $failureContext skipped because `$invocation` failed.")
                if (failureDetails.isNotBlank()) {
                    append('\n')
                    append(failureDetails)
                }
                append('\n')
            }
        }
        listener.onTaskOutput(taskId, output, ProcessOutputType.STDERR)
    }
}

internal data class MillCommandValueResult<T>(
    val command: MillCommandResult,
    val value: T,
)

internal data class MillExecutableProbeResult(
    val resolvedExecutable: String,
    val isValid: Boolean,
    val version: String?,
    val errorDetails: String?,
)

internal data class MillExecutableDiscovery(
    val projectWrapper: Path?,
    val pathExecutables: List<Path>,
)

internal object MillCommandLineUtil {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun buildMillCommand(
        projectRoot: Path,
        executableSource: MillExecutableSource,
        executablePath: String,
        arguments: List<String>,
    ): List<String> {
        val resolvedExecutable = resolveExecutable(projectRoot, executableSource, executablePath)
        return buildList {
            add(resolvedExecutable)
            addAll(arguments.filter(String::isNotBlank))
        }
    }

    internal fun discoverExecutables(projectRoot: Path): MillExecutableDiscovery {
        return MillExecutableDiscovery(
            projectWrapper = discoverWrapper(projectRoot),
            pathExecutables = discoverPathExecutables(),
        )
    }

    internal fun resolveExecutable(
        projectRoot: Path,
        executableSource: MillExecutableSource,
        configuredExecutablePath: String,
    ): String {
        val executableConfiguration =
            MillExecutableConfigurationUtil.normalize(executableSource, configuredExecutablePath)
        return when (executableConfiguration.source) {
            MillExecutableSource.PROJECT_DEFAULT_SCRIPT -> {
                discoverWrapper(projectRoot)?.let { wrapper ->
                    val resolved = wrapper.toString()
                    MillImportDebugLogger.info("Using detected Mill wrapper `$resolved`")
                    return resolved
                }
                MillImportDebugLogger.info("Falling back to Mill executable command `${MillConstants.defaultExecutable}`")
                MillConstants.defaultExecutable
            }

            MillExecutableSource.PATH -> {
                MillImportDebugLogger.info("Using Mill executable from PATH `${MillConstants.defaultExecutable}`")
                MillConstants.defaultExecutable
            }

            MillExecutableSource.MANUAL -> {
                resolveManualExecutable(projectRoot, executableConfiguration.manualPath)
            }
        }
    }

    internal fun probeExecutable(
        projectRoot: Path,
        executableSource: MillExecutableSource,
        executablePath: String,
    ): MillExecutableProbeResult {
        val resolvedExecutable = resolveExecutable(projectRoot, executableSource, executablePath)
        val declaredVersion = readDeclaredMillVersion(projectRoot)
        val probeSettings = MillExecutionSettings().apply {
            millExecutableSource = executableSource
            millExecutablePath = executablePath
        }
        var failureMessage: String? = null
        for (arguments in executableProbeArguments) {
            val command = runCommand(
                projectRoot = projectRoot,
                settings = probeSettings,
                arguments = arguments,
                timeoutMillis = executableProbeTimeoutMillis,
            )
            if (command.isSuccess) {
                return MillExecutableProbeResult(
                    resolvedExecutable = resolvedExecutable,
                    isValid = true,
                    version = parseMillVersion(command.stdout + "\n" + command.stderr) ?: declaredVersion,
                    errorDetails = null,
                )
            }
            if (failureMessage == null) {
                failureMessage = if (command.startupFailed) {
                    "The Mill process could not be started."
                } else if (command.timedOut) {
                    "The Mill process timed out after ${executableProbeTimeoutMillis / 1000} seconds."
                } else {
                    command.failureDetails.ifBlank {
                        "The Mill process exited with code ${command.exitCode ?: -1}."
                    }
                }
            }
            if (command.startupFailed || command.timedOut) {
                break
            }
        }
        return MillExecutableProbeResult(
            resolvedExecutable = resolvedExecutable,
            isValid = false,
            version = null,
            errorDetails = failureMessage,
        )
    }

    internal fun createCommandLine(
        projectRoot: Path,
        settings: MillExecutionSettings?,
        arguments: List<String>,
    ): GeneralCommandLine {
        val executableConfiguration = MillExecutableConfigurationUtil.normalize(
            settings?.millExecutableSource,
            settings?.millExecutablePath,
        )
        val command = buildMillCommand(
            projectRoot = projectRoot,
            executableSource = executableConfiguration.source,
            executablePath = executableConfiguration.manualPath,
            arguments = arguments,
        )
        return GeneralCommandLine(command)
            .withWorkingDirectory(projectRoot)
            .withEnvironment(settings?.env ?: emptyMap())
            .withParentEnvironmentType(
                if (settings?.isPassParentEnvs != false) {
                    GeneralCommandLine.ParentEnvironmentType.CONSOLE
                } else {
                    GeneralCommandLine.ParentEnvironmentType.NONE
                },
            )
    }

    private fun resolveManualExecutable(projectRoot: Path, configuredExecutablePath: String): String {
        val rawExecutable = configuredExecutablePath.trim()
        if (rawExecutable.isEmpty()) {
            MillImportDebugLogger.warn(
                "Manual Mill executable path is blank, falling back to PATH `${MillConstants.defaultExecutable}`",
            )
            return MillConstants.defaultExecutable
        }
        val configuredPath = runCatching { Path.of(rawExecutable) }.getOrNull()
        if (configuredPath != null) {
            if (configuredPath.isAbsolute) {
                val resolved = configuredPath.normalize().toString()
                MillImportDebugLogger.info("Using manually configured Mill executable `$resolved`")
                return resolved
            }
            val projectRelativePath = projectRoot.resolve(configuredPath).normalize()
            if (Files.isRegularFile(projectRelativePath)) {
                val resolved = projectRelativePath.toString()
                MillImportDebugLogger.info("Using manually configured project-relative Mill executable `$resolved`")
                return resolved
            }
        }
        MillImportDebugLogger.info("Using manually configured Mill executable command `$rawExecutable`")
        return rawExecutable
    }

    internal fun runCommand(
        projectRoot: Path,
        settings: MillExecutionSettings?,
        arguments: List<String>,
        timeoutMillis: Int = 0,
    ): MillCommandResult {
        val commandLine = createCommandLine(projectRoot, settings, arguments)
        return try {
            val processBuilder = commandLine.toProcessBuilder()
            val process = processBuilder.start()
            val charset = commandLine.charset
            val stdoutReader = readStreamAsync(process.inputStream, charset)
            val stderrReader = readStreamAsync(process.errorStream, charset)
            val finished = if (timeoutMillis > 0) {
                process.waitFor(timeoutMillis.toLong(), TimeUnit.MILLISECONDS)
            } else {
                process.waitFor()
                true
            }
            if (!finished) {
                process.destroyForcibly()
                process.waitFor(1, TimeUnit.SECONDS)
                closeQuietly(process.inputStream)
                closeQuietly(process.errorStream)
            }
            val stdout = awaitStreamText(stdoutReader)
            val stderr = awaitStreamText(stderrReader)
            val exitCode = if (finished) process.exitValue() else null
            MillCommandResult(
                projectRoot = projectRoot,
                arguments = arguments,
                commandLineString = commandLine.commandLineString,
                stdout = stdout,
                stderr = stderr,
                exitCode = exitCode,
                startupFailed = false,
                timedOut = !finished,
            )
        } catch (_: ExecutionException) {
            MillCommandResult(
                projectRoot = projectRoot,
                arguments = arguments,
                commandLineString = commandLine.commandLineString,
                stdout = "",
                stderr = "",
                exitCode = null,
                startupFailed = true,
                timedOut = false,
            )
        } catch (_: java.io.IOException) {
            MillCommandResult(
                projectRoot = projectRoot,
                arguments = arguments,
                commandLineString = commandLine.commandLineString,
                stdout = "",
                stderr = "",
                exitCode = null,
                startupFailed = true,
                timedOut = false,
            )
        }
    }

    private fun readStreamAsync(stream: InputStream, charset: java.nio.charset.Charset): FutureTask<String> {
        val task = FutureTask<String> {
            stream.bufferedReader(charset).use { it.readText() }
        }
        Thread(task, "MillCommandLineUtil-stream-reader").apply {
            isDaemon = true
            start()
        }
        return task
    }

    private fun awaitStreamText(task: FutureTask<String>): String {
        return try {
            task.get(1, TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
            task.cancel(true)
            ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun closeQuietly(stream: InputStream) {
        runCatching(stream::close)
    }

    internal fun resolveTargets(
        projectRoot: Path,
        settings: MillExecutionSettings?,
    ): MillCommandValueResult<List<String>> {
        val command = runCommand(projectRoot, settings, listOf("resolve", MillConstants.moduleDiscoveryQuery))
        val values = if (command.isSuccess) parseResolvedTargets(command.stdout) else emptyList()
        MillImportDebugLogger.info(
            "`mill ${command.invocation}` parsed ${values.size} target(s): ${MillImportDebugLogger.sample(values)}",
        )
        return MillCommandValueResult(command, values)
    }

    internal fun showStringValues(
        projectRoot: Path,
        settings: MillExecutionSettings?,
        showTarget: String,
    ): MillCommandValueResult<List<String>> {
        val command = runCommand(projectRoot, settings, listOf("show", showTarget))
        val values = if (command.isSuccess) {
            parseStringList(command.stdout).filter(String::isNotBlank)
        } else {
            emptyList()
        }
        MillImportDebugLogger.info(
            "`mill ${command.invocation}` parsed ${values.size} string value(s): ${MillImportDebugLogger.sample(values)}",
        )
        return MillCommandValueResult(command, values)
    }

    internal fun showStringValue(
        projectRoot: Path,
        settings: MillExecutionSettings?,
        showTarget: String,
    ): MillCommandValueResult<String?> {
        val command = runCommand(projectRoot, settings, listOf("show", showTarget))
        val value = if (command.isSuccess) parseSingleStringValue(command.stdout) else null
        MillImportDebugLogger.info("`mill ${command.invocation}` parsed single value=${value ?: "<null>"}")
        return MillCommandValueResult(command, value)
    }

    internal fun showPaths(
        projectRoot: Path,
        settings: MillExecutionSettings?,
        showTarget: String,
    ): MillCommandValueResult<List<Path>> {
        val values = showStringValues(projectRoot, settings, showTarget)
        val paths = values.value
            .asSequence()
            .mapNotNull { rawPath -> runCatching { Path.of(rawPath) }.getOrNull() }
            .map(Path::toAbsolutePath)
            .map(Path::normalize)
            .distinct()
            .toList()
        MillImportDebugLogger.info(
            "`mill ${values.command.invocation}` parsed ${paths.size} path(s): ${MillImportDebugLogger.sample(paths.map(Path::toString))}",
        )
        return MillCommandValueResult(values.command, paths)
    }

    internal fun showBinaryPaths(
        projectRoot: Path,
        settings: MillExecutionSettings?,
        showTarget: String,
    ): MillCommandValueResult<List<Path>> {
        val values = showPaths(projectRoot, settings, showTarget)
        return MillCommandValueResult(values.command, filterBinaryLibraryPaths(values.value))
    }

    internal fun readJavaHome(
        projectRoot: Path,
        settings: MillExecutionSettings?,
        targetPrefix: String,
    ): MillCommandValueResult<String?> {
        val command = runCommand(projectRoot, settings, listOf(targetPrefix, "-XshowSettings:properties", "-version"))
        val value = if (command.isSuccess) parseJavaHome(command.stdout + "\n" + command.stderr) else null
        MillImportDebugLogger.info("`mill ${command.invocation}` parsed javaHome=${value ?: "<project-sdk>"}")
        return MillCommandValueResult(command, value)
    }

    internal fun parseResolvedTargets(output: String): List<String> {
        return output.lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toList()
    }

    internal fun parsePathList(output: String): List<String> = parseStringList(output)

    internal fun parseStringList(output: String): List<String> = decodeOutputOrNull(
        output = output,
        strategy = ListSerializer(String.serializer()),
    ).orEmpty()
        .map(::normalizeOutputValue)

    internal fun parseSingleStringValue(output: String): String? = decodeOutputOrNull(
        output = output,
        strategy = String.serializer(),
    )?.let(::normalizeOutputValue)

    internal fun parseMillVersion(output: String): String? {
        return output.lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .firstNotNullOfOrNull { line ->
                millVersionPattern.find(line)?.groupValues?.getOrNull(1)
                    ?: standaloneVersionPattern.takeIf { it.matches(line) }
                        ?.matchEntire(line)?.groupValues?.getOrNull(1)
            }
    }

    internal fun readDeclaredMillVersion(projectRoot: Path): String? {
        val versionFile = projectRoot.resolve(MillConstants.versionFileName)
        if (!Files.isRegularFile(versionFile)) {
            return null
        }
        val rawContent = runCatching { Files.readString(versionFile) }.getOrNull() ?: return null
        return parseMillVersion(rawContent)
    }

    internal fun parseJavaHome(output: String): String? {
        return output.lineSequence()
            .map(String::trim)
            .firstNotNullOfOrNull { line ->
                line.substringAfter("java.home = ", missingDelimiterValue = "")
                    .takeIf(String::isNotBlank)
                    ?: line.substringAfter("java.home=", missingDelimiterValue = "").takeIf(String::isNotBlank)
            }
    }

    internal fun filterBinaryLibraryPaths(paths: Iterable<Path>): List<Path> {
        return paths.asSequence()
            .filter(Files::isRegularFile)
            .filter(::isBinaryLibraryPath)
            .distinct()
            .toList()
    }

    private fun discoverWrapper(projectRoot: Path): Path? {
        val candidates = buildList {
            add(projectRoot.resolve(MillConstants.wrapperScriptName))
            add(projectRoot.resolve(MillConstants.wrapperBatchName))
        }
        return candidates.firstOrNull(Files::isRegularFile)
    }

    private fun discoverPathExecutables(): List<Path> {
        val pathValue = System.getenv("PATH").orEmpty()
        if (pathValue.isBlank()) {
            return emptyList()
        }
        val candidateNames = executableNamesForCurrentPlatform()
        return pathValue.split(File.pathSeparatorChar)
            .asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .flatMap { directory ->
                candidateNames.asSequence().mapNotNull { executableName ->
                    runCatching { Path.of(directory).resolve(executableName).toAbsolutePath().normalize() }.getOrNull()
                }
            }
            .filter(Files::isRegularFile)
            .distinct()
            .toList()
    }

    private fun executableNamesForCurrentPlatform(): List<String> {
        return if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            listOf(MillConstants.wrapperBatchName, "mill.cmd", "mill.exe", MillConstants.wrapperScriptName)
        } else {
            listOf(MillConstants.wrapperScriptName)
        }
    }

    private fun <T> decodeOutputOrNull(output: String, strategy: DeserializationStrategy<T>): T? {
        val trimmed = output.trim()
        if (trimmed.isEmpty()) {
            return null
        }
        return runCatching { json.decodeFromString(strategy, trimmed) }.getOrNull()
    }

    internal fun normalizeOutputValue(value: String): String {
        if (value.startsWith("/") || windowsPathPattern.matches(value)) {
            return value
        }

        var candidate = value
        while (true) {
            val separatorIndex = candidate.indexOf(':')
            if (separatorIndex < 0 || separatorIndex == candidate.lastIndex) {
                return value
            }

            candidate = candidate.substring(separatorIndex + 1)
            if (candidate.startsWith("/") || windowsPathPattern.matches(candidate)) {
                return candidate
            }
        }
    }

    private fun isBinaryLibraryPath(path: Path): Boolean {
        val fileName = path.fileName?.toString().orEmpty().lowercase()
        return fileName.endsWith(".jar") || fileName.endsWith(".zip")
    }

    private val executableProbeArguments = listOf(
        listOf("--version"),
        listOf("version"),
        listOf("--help"),
    )
    private const val executableProbeTimeoutMillis = 10_000
    private val millVersionPattern =
        Regex("""(?i)\bmill(?:\s+build\s+tool)?(?:\s+version)?[: ]+v?([0-9][0-9A-Za-z.\-+_]*)""")
    private val standaloneVersionPattern = Regex("""^v?([0-9][0-9A-Za-z.\-+_]*)$""")
    private val windowsPathPattern = Regex("""^[A-Za-z]:[\\/].*""")
}
