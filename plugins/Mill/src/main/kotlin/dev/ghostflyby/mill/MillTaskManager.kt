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

package dev.ghostflyby.mill

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.ProcessOutput
import com.intellij.execution.process.ProcessOutputType
import com.intellij.openapi.externalSystem.model.ExternalSystemException
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import com.intellij.openapi.externalSystem.task.ExternalSystemTaskManager
import java.nio.file.Path

internal class MillTaskManager : ExternalSystemTaskManager<MillExecutionSettings> {
    override fun executeTasks(
        projectPath: String,
        id: ExternalSystemTaskId,
        settings: MillExecutionSettings,
        listener: ExternalSystemTaskNotificationListener,
    ) {
        val projectRoot = MillProjectResolverSupport.findProjectRoot(projectPath)
        val commandLine = createCommandLine(projectRoot, settings)

        listener.onStart(projectRoot.toString(), id)
        try {
            val output = CapturingProcessHandler(commandLine).runProcess()
            publishOutput(id, listener, output)
            when {
                output.isCancelled -> {
                    listener.onCancel(projectRoot.toString(), id)
                    throw ExternalSystemException("Mill task execution was cancelled.")
                }

                output.isTimeout -> {
                    val error = ExternalSystemException("Mill task execution timed out.")
                    listener.onFailure(projectRoot.toString(), id, error)
                    throw error
                }

                output.exitCode != 0 -> {
                    val error = ExternalSystemException(buildFailureMessage(output))
                    listener.onFailure(projectRoot.toString(), id, error)
                    throw error
                }

                else -> listener.onSuccess(projectRoot.toString(), id)
            }
        } catch (error: ExecutionException) {
            val wrapped = ExternalSystemException("Failed to start Mill process.", error)
            listener.onFailure(projectRoot.toString(), id, wrapped)
            throw wrapped
        } finally {
            listener.onEnd(projectRoot.toString(), id)
        }
    }

    override fun cancelTask(id: ExternalSystemTaskId, listener: ExternalSystemTaskNotificationListener): Boolean = false

    private fun createCommandLine(projectRoot: Path, settings: MillExecutionSettings): GeneralCommandLine {
        val command = MillCommandLineUtil.buildMillCommand(
            executable = settings.millExecutablePath,
            jvmOptionsText = settings.millJvmOptions,
            arguments = settings.tasks.filter(String::isNotBlank) + settings.arguments.filter(String::isNotBlank),
        )
        if (command.size == 1) {
            throw ExternalSystemException("No Mill tasks were provided for execution.")
        }

        return GeneralCommandLine(command)
            .withWorkingDirectory(projectRoot)
            .withEnvironment(settings.env)
            .withParentEnvironmentType(
                if (settings.isPassParentEnvs) {
                    GeneralCommandLine.ParentEnvironmentType.CONSOLE
                } else {
                    GeneralCommandLine.ParentEnvironmentType.NONE
                },
            )
    }

    private fun publishOutput(
        id: ExternalSystemTaskId,
        listener: ExternalSystemTaskNotificationListener,
        output: ProcessOutput,
    ) {
        if (output.stdout.isNotBlank()) {
            listener.onTaskOutput(id, output.stdout, ProcessOutputType.STDOUT)
        }
        if (output.stderr.isNotBlank()) {
            listener.onTaskOutput(id, output.stderr, ProcessOutputType.STDERR)
        }
    }

    private fun buildFailureMessage(output: ProcessOutput): String {
        return buildString {
            append("Mill task failed with exit code ${output.exitCode}.")
            if (output.stderr.isNotBlank()) {
                append("\n\n")
                append(output.stderr.trim())
            } else if (output.stdout.isNotBlank()) {
                append("\n\n")
                append(output.stdout.trim())
            }
        }
    }
}
