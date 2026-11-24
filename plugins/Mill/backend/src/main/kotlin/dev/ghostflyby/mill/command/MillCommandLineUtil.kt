/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mill.command

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.execution.process.ProcessOutputType
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import com.intellij.util.text.SemVer
import dev.ghostflyby.mill.Bundle
import dev.ghostflyby.mill.MillConstants
import dev.ghostflyby.mill.MillImportDebugLogger
import dev.ghostflyby.mill.settings.MillExecutableConfiguration
import dev.ghostflyby.mill.settings.MillExecutableConfigurationUtil
import dev.ghostflyby.mill.settings.MillExecutableSource
import dev.ghostflyby.mill.settings.MillExecutionSettings
import dev.ghostflyby.mill.settings.MillExecutionTarget
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
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

    internal fun discoverExecutables(
        projectRoot: Path,
        target: MillExecutionTarget = MillExecutionTarget.Local,
    ): MillExecutableDiscovery {
        return MillExecutableDiscovery(
            projectWrapper = discoverWrapper(projectRoot),
            pathExecutables = if (target is MillExecutionTarget.Wsl) {
                discoverTargetPathExecutables(projectRoot, target)
            } else {
                discoverPathExecutables()
            },
        )
    }

    internal fun resolveExecutable(
        projectRoot: Path,
        executableSource: MillExecutableSource,
        configuredExecutablePath: String,
        target: MillExecutionTarget = MillExecutionTarget.Local,
    ): String {
        val resolved = resolveLocalExecutable(projectRoot, executableSource, configuredExecutablePath)
        return if (target is MillExecutionTarget.Wsl) {
            toHostPath(target, resolveTargetExecutable(projectRoot, target, resolved))
        } else {
            resolved
        }
    }

    private fun resolveLocalExecutable(
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
                MillImportDebugLogger.info("Falling back to Mill executable command `${MillConstants.DEFAULT_EXECUTABLE}`")
                MillConstants.DEFAULT_EXECUTABLE
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
        target: MillExecutionTarget = MillExecutionTarget.Local,
    ): MillExecutableProbeResult {
        val resolvedExecutable = resolveExecutable(projectRoot, executableSource, executablePath, target)
        val displayResolvedExecutable = resolveDisplayExecutable(executableSource, executablePath, resolvedExecutable)
        val probeSettings = MillExecutionSettings().apply {
            millExecutableSource = executableSource
            millExecutablePath = executablePath
            this.target = target
        }
        val command = runCommand(
            projectRoot = projectRoot,
            settings = probeSettings,
            arguments = executableProbeArguments,
            timeoutMillis = EXECUTABLE_PROBE_TIMEOUT_MILLIS,
        )
        if (command.isSuccess) {
            val version = parseSingleStringValue(command.stdout)
            val versionError = validateSupportedMillVersion(version)
            return MillExecutableProbeResult(
                resolvedExecutable = displayResolvedExecutable,
                isValid = versionError == null,
                version = version,
                errorDetails = versionError,
            )
        }
        val failureMessage = if (command.startupFailed) {
            "The Mill process could not be started."
        } else if (command.timedOut) {
            "The Mill process timed out after ${EXECUTABLE_PROBE_TIMEOUT_MILLIS / 1000} seconds."
        } else {
            command.failureDetails.ifBlank {
                "The Mill process exited with code ${command.exitCode ?: -1}."
            }
        }
        return MillExecutableProbeResult(
            resolvedExecutable = displayResolvedExecutable,
            isValid = false,
            version = null,
            errorDetails = failureMessage,
        )
    }

    private fun resolveDisplayExecutable(
        executableSource: MillExecutableSource,
        executablePath: String,
        resolvedExecutable: String,
    ): String {
        if (resolvedExecutable != MillConstants.DEFAULT_EXECUTABLE) {
            return resolvedExecutable
        }
        if (executableSource != MillExecutableSource.MANUAL || executablePath.trim() != MillConstants.DEFAULT_EXECUTABLE) {
            return resolvedExecutable
        }
        return PathEnvironmentVariableUtil.findInPath(MillConstants.DEFAULT_EXECUTABLE)
            ?.toPath()
            ?.toAbsolutePath()
            ?.normalize()
            ?.toString()
            ?: findExecutableByCommandLookup()
            ?: resolvedExecutable
    }

    private fun findExecutableByCommandLookup(): String? {
        val commandName = MillConstants.DEFAULT_EXECUTABLE
        val locatorCommand = when {
            System.getProperty("os.name").startsWith("Windows", ignoreCase = true) -> listOf("where.exe", commandName)
            else -> listOf("/usr/bin/env", "which", commandName)
        }
        val commandLine = GeneralCommandLine(locatorCommand)
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
        return try {
            val process = commandLine.toProcessBuilder().start()
            val stdoutReader = readStreamAsync(process.inputStream, commandLine.charset)
            val stderrReader = readStreamAsync(process.errorStream, commandLine.charset)
            val finished = process.waitFor(2, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                process.waitFor(1, TimeUnit.SECONDS)
                closeQuietly(process.inputStream)
                closeQuietly(process.errorStream)
                return null
            }
            val stdout = awaitStreamText(stdoutReader)
            awaitStreamText(stderrReader)
            stdout.lineSequence()
                .map(String::trim)
                .firstOrNull(String::isNotEmpty)
        } catch (_: Exception) {
            null
        }
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
        val target = settings?.target ?: MillExecutionTarget.Local
        val command = buildTargetCommand(projectRoot, target, executableConfiguration, arguments)
        val commandLine = GeneralCommandLine(command)
        if (target !is MillExecutionTarget.Wsl) {
            // `--cd` already selects the working directory inside the distribution; handing a
            // \\wsl.localhost path to CreateProcess as the working directory is not reliable.
            commandLine.withWorkingDirectory(projectRoot)
        }
        return commandLine
            .withEnvironment(settings?.env ?: emptyMap())
            .withParentEnvironmentType(
                if (settings?.isPassParentEnvs != false) {
                    GeneralCommandLine.ParentEnvironmentType.CONSOLE
                } else {
                    GeneralCommandLine.ParentEnvironmentType.NONE
                },
            )
    }

    // Starts the process. WSL commands bypass GeneralCommandLine's process creation on purpose:
    // for remote-looking targets it may route the command through the platform's EEL daemon,
    // which some environments refuse to launch (an elevated Windows CI runner fails with
    // ELEVATED_PRIVILEGES), while wsl.exe alone needs no platform machinery.
    internal fun startProcess(
        commandLine: GeneralCommandLine,
        settings: MillExecutionSettings?,
    ): Process {
        val target = settings?.target ?: MillExecutionTarget.Local
        if (target !is MillExecutionTarget.Wsl) {
            return commandLine.toProcessBuilder().start()
        }
        val builder = ProcessBuilder(listOf(commandLine.exePath) + commandLine.parametersList.list)
        val environment = builder.environment()
        if (settings?.isPassParentEnvs == false) {
            environment.clear()
        }
        environment.putAll(settings?.env ?: emptyMap())
        return builder.start()
    }

    // wsl.exe forwards the distribution's UTF-8 output; the local charset is only right for
    // native commands.
    internal fun processCharset(commandLine: GeneralCommandLine, settings: MillExecutionSettings?): Charset =
        if (settings?.target is MillExecutionTarget.Wsl) Charsets.UTF_8 else commandLine.charset

    // Bare commands (e.g. `mill`) must be looked up in the distribution's login shell — the host
    // PATH knows nothing about the profile that sets up in-distribution tools. Absolute, unc and
    // project-relative executables already resolve from the target project root, so they pass
    // through untouched.
    internal fun resolveTargetExecutable(
        projectRoot: Path,
        target: MillExecutionTarget.Wsl,
        resolved: String,
    ): String {
        val candidate = toTargetExecutablePath(resolved)
        if (!simpleCommandPattern.matches(candidate)) {
            return candidate
        }
        val cacheKey = "${target.distribution}:$candidate"
        targetCommandPaths[cacheKey]?.let { return it }
        val discovered = findTargetCommandPath(projectRoot, target, candidate) ?: return candidate
        targetCommandPaths[cacheKey] = discovered
        return discovered
    }

    // `command -v` in a login shell is the only reliable way to locate a command inside the
    // distribution; the returned path is a target-style path usable as argv[0] after `--exec`.
    internal fun findTargetCommandPath(
        projectRoot: Path,
        target: MillExecutionTarget.Wsl,
        command: String,
    ): String? {
        if (!simpleCommandPattern.matches(command)) {
            return null
        }
        val commandLine = GeneralCommandLine(
            buildWslCommandLine(target, projectRoot, listOf("sh", "-lc", "command -v $command")),
        )
        val discoverySettings = MillExecutionSettings().apply { this.target = target }
        val result = runProcess(
            commandLine = commandLine,
            settings = discoverySettings,
            projectRoot = projectRoot,
            arguments = emptyList(),
            timeoutMillis = EXECUTABLE_PROBE_TIMEOUT_MILLIS,
        )
        if (!result.isSuccess) {
            return null
        }
        return result.stdout.lineSequence()
            .map(String::trim)
            .firstOrNull { it.startsWith("/") }
    }

    private fun buildTargetCommand(
        projectRoot: Path,
        target: MillExecutionTarget,
        executableConfiguration: MillExecutableConfiguration,
        arguments: List<String>,
    ): List<String> = when (target) {
        MillExecutionTarget.Local -> buildMillCommand(
            projectRoot = projectRoot,
            executableSource = executableConfiguration.source,
            executablePath = executableConfiguration.manualPath,
            arguments = arguments,
        )

        is MillExecutionTarget.Wsl -> buildWslCommandLine(
            target = target,
            projectRoot = projectRoot,
            argv = buildList {
                add(
                    resolveTargetExecutable(
                        projectRoot,
                        target,
                        resolveLocalExecutable(projectRoot, executableConfiguration.source, executableConfiguration.manualPath),
                    ),
                )
                addAll(arguments.filter(String::isNotBlank))
            },
        )

        is MillExecutionTarget.Eel -> throw ExecutionException(
            Bundle.message("execution.target.unsupported", target.description),
        )
    }

    // Runs the given argv inside the WSL distribution. `--exec` bypasses the default shell so
    // argv quoting survives, and `--cd` makes wrapper scripts resolve against the project root.
    internal fun buildWslCommandLine(
        target: MillExecutionTarget.Wsl,
        projectRoot: Path,
        argv: List<String>,
    ): List<String> = buildList {
        add(WSL_EXE)
        add("-d")
        add(target.distribution)
        add("--cd")
        add(toTargetPath(projectRoot))
        add("--exec")
        addAll(argv)
    }

    private fun resolveManualExecutable(projectRoot: Path, configuredExecutablePath: String): String {
        val rawExecutable = configuredExecutablePath.trim()
        if (rawExecutable.isEmpty()) {
            MillImportDebugLogger.info(
                "Manual Mill executable path is blank, falling back to PATH `${MillConstants.DEFAULT_EXECUTABLE}`",
            )
            return MillConstants.DEFAULT_EXECUTABLE
        }
        val configuredPath = runCatching { Path.of(rawExecutable) }.getOrNull()
        if (configuredPath != null) {
            if (configuredPath.isAbsolute) {
                val resolved = configuredPath.normalize().toString()
                MillImportDebugLogger.info("Using manually configured Mill executable `$resolved`")
                return resolved
            }
            if (rawExecutable.any { it == '/' || it == '\\' }) {
                val projectRelativePath = projectRoot.resolve(configuredPath).normalize()
                if (Files.isRegularFile(projectRelativePath)) {
                    val resolved = projectRelativePath.toString()
                    MillImportDebugLogger.info("Using manually configured project-relative Mill executable `$resolved`")
                    return resolved
                }
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
    ): MillCommandResult = runProcess(createCommandLine(projectRoot, settings, arguments), settings, projectRoot, arguments, timeoutMillis)

    private fun runProcess(
        commandLine: GeneralCommandLine,
        settings: MillExecutionSettings?,
        projectRoot: Path,
        arguments: List<String>,
        timeoutMillis: Int,
    ): MillCommandResult {
        var process: Process? = null
        return try {
            process = startProcess(commandLine, settings)
            val charset = processCharset(commandLine, settings)
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
        } catch (interrupted: InterruptedException) {
            process?.destroyForcibly()
            process?.let {
                closeQuietly(it.inputStream)
                closeQuietly(it.errorStream)
            }
            Thread.currentThread().interrupt()
            throw interrupted
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

    private fun readStreamAsync(stream: InputStream, charset: Charset): FutureTask<String> {
        val task: FutureTask<String> = FutureTask {
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
        val command = runCommand(projectRoot, settings, listOf("show", "resolve", MillConstants.MODULE_DISCOVERY_QUERY))
        val values = if (command.isSuccess) parseStringList(command.stdout).filter(String::isNotBlank) else emptyList()
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
        val target = settings?.target ?: MillExecutionTarget.Local
        val values = if (command.isSuccess) {
            parseStringList(command.stdout).filter(String::isNotBlank).map { toHostPath(target, it) }
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
        val target = settings?.target ?: MillExecutionTarget.Local
        val value = if (command.isSuccess) {
            parseSingleStringValue(command.stdout)?.let { toHostPath(target, it) }
        } else {
            null
        }
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
        val target = settings?.target ?: MillExecutionTarget.Local
        val value = if (command.isSuccess) {
            parseJavaHome(command.stdout + "\n" + command.stderr)?.let { toHostPath(target, it) }
        } else {
            null
        }
        MillImportDebugLogger.info("`mill ${command.invocation}` parsed javaHome=${value ?: "<project-sdk>"}")
        return MillCommandValueResult(command, value)
    }

    internal fun parseStringList(output: String): List<String> = decodeOutputOrNull(
        output = output,
        strategy = ListSerializer(String.serializer()),
    ).orEmpty()
        .map(::normalizeOutputValue)

    internal fun parseSingleStringValue(output: String): String? = decodeOutputOrNull(
        output = output,
        strategy = String.serializer(),
    )?.let(::normalizeOutputValue)

    internal fun validateSupportedMillVersion(version: String?): String? {
        val normalizedVersion = version?.trim().takeUnless { it.isNullOrEmpty() }
            ?: return Bundle.message(
                "settings.mill.executable.status.invalid.minimum.unknown",
                MINIMUM_SUPPORTED_MILL_VERSION,
            )
        val parsedVersion = SemVer.parseFromText(normalizedVersion)
            ?: return Bundle.message(
                "settings.mill.executable.status.invalid.minimum.unknown",
                MINIMUM_SUPPORTED_MILL_VERSION,
            )
        return if (parsedVersion < minimumSupportedMillSemVer) {
            Bundle.message(
                "settings.mill.executable.status.invalid.minimum",
                MINIMUM_SUPPORTED_MILL_VERSION,
                normalizedVersion,
            )
        } else {
            null
        }
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
            add(projectRoot.resolve(MillConstants.WRAPPER_SCRIPT_NAME))
            add(projectRoot.resolve(MillConstants.WRAPPER_BATCH_NAME))
        }
        return candidates.firstOrNull(Files::isRegularFile)
    }

    private fun discoverPathExecutables(): List<Path> {
        return executableNamesForCurrentPlatform()
            .asSequence()
            .flatMap { executableName ->
                PathEnvironmentVariableUtil.findAllExeFilesInPath(executableName)
                    .asSequence()
                    .map(java.io.File::toPath)
            }
            .map(Path::toAbsolutePath)
            .map(Path::normalize)
            .distinct()
            .toList()
    }

    // PATH discovery must happen inside the target: the host PATH knows nothing about the
    // distribution's shell profile.
    private fun discoverTargetPathExecutables(projectRoot: Path, target: MillExecutionTarget.Wsl): List<Path> {
        val discovered = findTargetCommandPath(projectRoot, target, MillConstants.DEFAULT_EXECUTABLE)
        if (discovered == null) {
            MillImportDebugLogger.info(
                "Target PATH discovery for `${target.distribution}` produced no `${MillConstants.DEFAULT_EXECUTABLE}`",
            )
            return emptyList()
        }
        return listOf(discovered)
            .map { toHostPath(target, it) }
            .mapNotNull { runCatching { Path.of(it).toAbsolutePath().normalize() }.getOrNull() }
            .distinct()
    }

    // UNC \\wsl.localhost\<distro>\foo or \\wsl$\<distro>\foo -> /foo. Values without a WSL UNC
    // prefix (bare commands, target-style paths) are returned unchanged.
    internal fun toTargetPath(hostPath: Path): String {
        val normalized = hostPath.toString().replace('/', '\\')
        val prefix = wslUncPrefix.find(normalized) ?: return normalized
        return "/" + normalized.substring(prefix.value.length).replace('\\', '/')
    }

    // Absolute target paths (Mill `show` output) become \\wsl.localhost\<distro>\... UNC paths so
    // the IDE-side project model resolves real files; non-absolute values pass through untouched.
    internal fun toHostPath(target: MillExecutionTarget, value: String): String = when (target) {
        is MillExecutionTarget.Wsl -> if (value.startsWith("/")) {
            """\\wsl.localhost\${target.distribution}\${value.removePrefix("/").replace('/', '\\')}"""
        } else {
            value
        }

        else -> value
    }

    private fun toTargetExecutablePath(resolved: String): String = when {
        resolved.startsWith("\\\\wsl.localhost\\", ignoreCase = true) || resolved.startsWith("\\\\wsl$\\", ignoreCase = true) ->
            toTargetPath(Path.of(resolved))

        else -> resolved
    }

    private fun executableNamesForCurrentPlatform(): List<String> {
        return if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            listOf(MillConstants.WRAPPER_BATCH_NAME, "mill.cmd", "mill.exe", MillConstants.WRAPPER_SCRIPT_NAME)
        } else {
            listOf(MillConstants.WRAPPER_SCRIPT_NAME)
        }
    }

    private fun <T> decodeOutputOrNull(output: String, strategy: DeserializationStrategy<T>): T? {
        return extractJsonValueElement(output)?.let { element ->
            runCatching { json.decodeFromJsonElement(strategy, element) }.getOrNull()
        }
    }

    private fun extractJsonValueElement(output: String): JsonElement? {
        val trimmed = output.trim()
        if (trimmed.isEmpty()) {
            return null
        }
        val parsed = runCatching { json.parseToJsonElement(trimmed) }.getOrNull() ?: return null
        return (parsed as? JsonObject)?.get(JSON_VALUE_FIELD_NAME) ?: parsed
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

    private val executableProbeArguments = listOf("show", "version")
    private const val EXECUTABLE_PROBE_TIMEOUT_MILLIS = 10_000
    private const val MINIMUM_SUPPORTED_MILL_VERSION = "1.1.5"
    private val minimumSupportedMillSemVer = requireNotNull(SemVer.parseFromText(MINIMUM_SUPPORTED_MILL_VERSION))
    private const val JSON_VALUE_FIELD_NAME = "value"
    private val windowsPathPattern = Regex("""^[A-Za-z]:[\\/].*""")
    private const val WSL_EXE = "wsl.exe"
    private val wslUncPrefix = Regex("""^\\\\wsl(\.localhost|\$)\\[^\\]+\\?""", RegexOption.IGNORE_CASE)
    private val simpleCommandPattern = Regex("""^[A-Za-z0-9._+-]+$""")

    // Positive cache only: a command missing now can appear after the user installs it in the
    // distribution, so misses must not stick.
    private val targetCommandPaths = ConcurrentHashMap<String, String>()
}
