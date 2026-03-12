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
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.ProcessOutputType
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import com.intellij.util.execution.ParametersListUtil
import dev.ghostflyby.mill.MillConstants
import dev.ghostflyby.mill.MillExecutionSettings
import dev.ghostflyby.mill.MillImportDebugLogger
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

internal data class MillCommandResult(
    val projectRoot: Path,
    val arguments: List<String>,
    val commandLineString: String,
    val stdout: String,
    val stderr: String,
    val exitCode: Int?,
    val startupFailed: Boolean,
) {
    val invocation: String
        get() = arguments.joinToString(" ")

    val isSuccess: Boolean
        get() = !startupFailed && exitCode == 0

    val failureDetails: String
        get() = stderr.ifBlank { stdout }.trim()

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

internal object MillCommandLineUtil {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun buildMillCommand(
        projectRoot: Path,
        executable: String,
        jvmOptionsText: String,
        arguments: List<String>,
    ): List<String> {
        val resolvedExecutable = resolveExecutable(projectRoot, executable)
        return buildList {
            add(resolvedExecutable)
            addAll(parseOptions(jvmOptionsText))
            addAll(arguments.filter(String::isNotBlank))
        }
    }

    internal fun resolveExecutable(projectRoot: Path, configuredExecutable: String): String {
        val rawExecutable = configuredExecutable.trim()
        if (rawExecutable.isNotEmpty()) {
            val configuredPath = runCatching { Path.of(rawExecutable) }.getOrNull()
            if (configuredPath != null) {
                if (configuredPath.isAbsolute) {
                    val resolved = configuredPath.normalize().toString()
                    MillImportDebugLogger.info("Using configured Mill executable `$resolved`")
                    return resolved
                }
                val projectRelativePath = projectRoot.resolve(configuredPath).normalize()
                if (Files.isRegularFile(projectRelativePath)) {
                    val resolved = projectRelativePath.toString()
                    MillImportDebugLogger.info("Using project-relative Mill executable `$resolved`")
                    return resolved
                }
            }
            if (rawExecutable != MillConstants.defaultExecutable) {
                MillImportDebugLogger.info("Using configured Mill executable command `$rawExecutable`")
                return rawExecutable
            }
        }

        discoverWrapper(projectRoot)?.let { wrapper ->
            val resolved = wrapper.toString()
            MillImportDebugLogger.info("Using detected Mill wrapper `$resolved`")
            return resolved
        }
        val resolved = rawExecutable.ifBlank { MillConstants.defaultExecutable }
        MillImportDebugLogger.info("Falling back to Mill executable command `$resolved`")
        return resolved
    }

    internal fun parseOptions(rawValue: String): List<String> {
        return ParametersListUtil.parse(rawValue.trim(), false, true)
            .map(String::trim)
            .filter(String::isNotEmpty)
    }

    internal fun createCommandLine(
        projectRoot: Path,
        settings: MillExecutionSettings?,
        arguments: List<String>,
    ): GeneralCommandLine {
        val command = buildMillCommand(
            projectRoot = projectRoot,
            executable = settings?.millExecutablePath ?: MillConstants.defaultExecutable,
            jvmOptionsText = settings?.millJvmOptions.orEmpty(),
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

    internal fun runCommand(
        projectRoot: Path,
        settings: MillExecutionSettings?,
        arguments: List<String>,
    ): MillCommandResult {
        val commandLine = createCommandLine(projectRoot, settings, arguments)
        return try {
            val output = CapturingProcessHandler(commandLine).runProcess()
            MillCommandResult(
                projectRoot = projectRoot,
                arguments = arguments,
                commandLineString = commandLine.commandLineString,
                stdout = output.stdout,
                stderr = output.stderr,
                exitCode = output.exitCode,
                startupFailed = false,
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
            )
        }
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
        .map(::normalizeValue)

    internal fun parseSingleStringValue(output: String): String? = decodeOutputOrNull(
        output = output,
        strategy = String.serializer(),
    )?.let(::normalizeValue)

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

    private fun <T> decodeOutputOrNull(output: String, strategy: DeserializationStrategy<T>): T? {
        val trimmed = output.trim()
        if (trimmed.isEmpty()) {
            return null
        }
        return runCatching { json.decodeFromString(strategy, trimmed) }.getOrNull()
    }

    private fun normalizeValue(value: String): String {
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

    private val windowsPathPattern = Regex("""^[A-Za-z]:[\\/].*""")
}
