/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.spotless.gradle

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.util.system.OS
import dev.ghostflyby.spotless.SpotlessDaemonHost
import org.jetbrains.plugins.gradle.service.GradleInstallationManager
import org.jetbrains.plugins.gradle.settings.DistributionType
import org.jetbrains.plugins.gradle.settings.GradleProjectSettings
import org.jetbrains.plugins.gradle.settings.GradleSettings
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.isRegularFile
import kotlin.io.path.writeText

private const val spotlessDaemonTaskName = ":spotlessDaemon"
private const val spotlessDaemonUnixSocketProperty = "dev.ghostflyby.spotless.daemon.unixsocket"

private val logger = logger<SpotlessGradleDaemonProcess>()

internal data class SpotlessGradleDaemonProcess(
    val host: SpotlessDaemonHost.Unix,
    val handler: KillableColoredProcessHandler,
    val initScript: Path,
) {
    fun start() {
        handler.startNotify()
    }

    fun destroyProcess() {
        if (handler.isProcessTerminated || handler.isProcessTerminating) {
            return
        }
        handler.destroyProcess()
    }
}

internal fun startGradleSpotlessDaemon(
    project: Project,
    externalProject: Path,
    unixSocketPath: Path,
    host: SpotlessDaemonHost.Unix,
    onProcessTerminated: () -> Unit,
): SpotlessGradleDaemonProcess {
    val settings = project.service<SpotlessGradleSettings>()
    val initScript = host.workingDirectory.resolve("spotless-daemon.init.gradle")
    initScript.writeText(
        spotlessDaemonInitScript(
            daemonVersion = settings.gradleDaemonVersion.trim(),
            daemonJar = settings.gradleDaemonJar.trim(),
        ),
    )

    val commandLine = createGradleSpotlessDaemonCommandLine(
        project = project,
        externalProject = externalProject,
        unixSocketPath = unixSocketPath,
        initScript = initScript,
    )
    val handler = KillableColoredProcessHandler(commandLine)
    handler.addProcessListener(
        object : ProcessListener {
            override fun processTerminated(event: ProcessEvent) {
                onProcessTerminated()
            }
        },
    )
    return SpotlessGradleDaemonProcess(host, handler, initScript)
}

private fun createGradleSpotlessDaemonCommandLine(
    project: Project,
    externalProject: Path,
    unixSocketPath: Path,
    initScript: Path,
): GeneralCommandLine {
    val executable = resolveGradleExecutable(project, externalProject)
    val javaHome = service<GradleInstallationManager>().getGradleJvmPath(project, externalProject.absolutePathString())
    val commandLine = GeneralCommandLine()
        .withExePath(executable)
        .withWorkingDirectory(externalProject)
        .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
        .withParameters(
            "--init-script",
            initScript.absolutePathString(),
            spotlessDaemonTaskName,
            "-P$spotlessDaemonUnixSocketProperty=${unixSocketPath.toAbsolutePath()}",
        )
    if (!javaHome.isNullOrBlank()) {
        commandLine.withEnvironment("JAVA_HOME", javaHome)
    }
    return commandLine
}

private fun resolveGradleExecutable(project: Project, externalProject: Path): String {
    val projectSettings = findGradleProjectSettings(project, externalProject)
    val distributionType = projectSettings?.distributionType
    val gradleHomePath = projectSettings?.gradleHomePath
    val wrapper = externalProject.resolve(gradleStartScriptName("gradlew"))
    return when {
        distributionType?.isWrapped == true && wrapper.isRegularFile() -> wrapper.absolutePathString()
        distributionType == DistributionType.LOCAL -> gradleHomePath?.toGradleStartScript()
            ?: pathGradleExecutable()

        distributionType == DistributionType.BUNDLED -> gradleHomeFromIde(
            project,
            externalProject,
        )?.toGradleStartScript()
            ?: pathGradleExecutable()

        wrapper.isRegularFile() -> wrapper.absolutePathString()
        gradleHomePath != null -> gradleHomePath.toGradleStartScript()
            ?: pathGradleExecutable()

        else -> pathGradleExecutable()
    }
}

private fun findGradleProjectSettings(
    project: Project,
    externalProject: Path,
): GradleProjectSettings? {
    val normalized = externalProject.toAbsolutePath().normalize()
    return GradleSettings.getInstance(project).linkedProjectsSettings
        .firstOrNull { settings ->
            settings.externalProjectPath?.let { Path.of(it).toAbsolutePath().normalize() == normalized } == true
        } as? GradleProjectSettings
}

private fun gradleHomeFromIde(project: Project, externalProject: Path): Path? {
    return service<GradleInstallationManager>().getGradleHomePath(project, externalProject.absolutePathString())
}

private fun Path.toGradleStartScript(): String? {
    val executable = resolve("bin").resolve(gradleStartScriptName("gradle"))
    if (executable.isRegularFile()) {
        return executable.absolutePathString()
    }
    logger.warn("Gradle home does not contain ${executable.fileName}: $this")
    return null
}

private fun pathGradleExecutable(): String {
    return gradleStartScriptName("gradle")
}

private fun gradleStartScriptName(baseName: String): String {
    return if (OS.CURRENT == OS.Windows) "$baseName.bat" else baseName
}

internal fun cleanupGradleDaemonProcess(process: SpotlessGradleDaemonProcess?) {
    process?.destroyProcess()
    process?.let {
        runCatching {
            Files.deleteIfExists(it.initScript)
        }.onFailure { error ->
            logger.warn("Failed to delete Spotless daemon init script: ${it.initScript}", error)
        }
    }
}
