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
import com.intellij.execution.process.ProcessOutputType
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import java.nio.file.Files
import java.nio.file.Path

internal object MillClasspathResolver {
    fun resolveBinaryLibraryPaths(
        root: Path,
        settings: MillExecutionSettings?,
        taskId: ExternalSystemTaskId,
        listener: ExternalSystemTaskNotificationListener,
    ): List<Path> {
        val output = try {
            CapturingProcessHandler(createCommandLine(root, settings)).runProcess()
        } catch (_: ExecutionException) {
            listener.onTaskOutput(
                taskId,
                "Mill dependency resolution skipped because the Mill process could not be started.\n",
                ProcessOutputType.STDERR,
            )
            return emptyList()
        }

        if (output.exitCode != 0) {
            val details = output.stderr.ifBlank { output.stdout }.trim()
            listener.onTaskOutput(
                taskId,
                buildString {
                    append("Mill dependency resolution skipped because `show __.compileClasspath` failed.")
                    if (details.isNotBlank()) {
                        append('\n')
                        append(details)
                    }
                    append('\n')
                },
                ProcessOutputType.STDERR,
            )
            return emptyList()
        }

        return parsePathList(output.stdout)
            .asSequence()
            .mapNotNull { rawPath -> runCatching { Path.of(rawPath) }.getOrNull() }
            .map(Path::toAbsolutePath)
            .map(Path::normalize)
            .filter(Files::isRegularFile)
            .filter(::isBinaryLibraryPath)
            .distinct()
            .toList()
    }

    internal fun parsePathList(output: String): List<String> {
        val arrayText = extractLastArray(output) ?: return emptyList()
        return quotedStringPattern
            .findAll(arrayText)
            .map { match -> unescapeJsonString(match.groupValues[1]) }
            .filter(String::isNotBlank)
            .toList()
    }

    private fun createCommandLine(root: Path, settings: MillExecutionSettings?): GeneralCommandLine {
        val executable = settings?.millExecutablePath?.ifBlank { MillConstants.defaultExecutable }
            ?: MillConstants.defaultExecutable
        return GeneralCommandLine(listOf(executable, "show", "__.compileClasspath"))
            .withWorkingDirectory(root)
            .withEnvironment(settings?.env ?: emptyMap())
            .withParentEnvironmentType(
                if (settings?.isPassParentEnvs != false) {
                    GeneralCommandLine.ParentEnvironmentType.CONSOLE
                } else {
                    GeneralCommandLine.ParentEnvironmentType.NONE
                },
            )
    }

    private fun extractLastArray(output: String): String? {
        val end = output.lastIndexOf(']')
        if (end < 0) {
            return null
        }

        var depth = 0
        for (index in end downTo 0) {
            when (output[index]) {
                ']' -> depth += 1
                '[' -> {
                    depth -= 1
                    if (depth == 0) {
                        return output.substring(index, end + 1)
                    }
                }
            }
        }
        return null
    }

    private fun isBinaryLibraryPath(path: Path): Boolean {
        val fileName = path.fileName?.toString().orEmpty().lowercase()
        return fileName.endsWith(".jar") || fileName.endsWith(".zip")
    }

    private fun unescapeJsonString(value: String): String {
        val result = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val current = value[index]
            if (current != '\\') {
                result.append(current)
                index += 1
                continue
            }

            if (index + 1 >= value.length) {
                result.append('\\')
                break
            }

            when (val escaped = value[index + 1]) {
                '"', '\\', '/' -> result.append(escaped)
                'b' -> result.append('\b')
                'f' -> result.append('\u000C')
                'n' -> result.append('\n')
                'r' -> result.append('\r')
                't' -> result.append('\t')
                'u' -> {
                    val hexStart = index + 2
                    val hexEnd = hexStart + 4
                    if (hexEnd <= value.length) {
                        val codePoint = value.substring(hexStart, hexEnd).toIntOrNull(16)
                        if (codePoint != null) {
                            result.append(codePoint.toChar())
                            index = hexEnd
                            continue
                        }
                    }
                    result.append("\\u")
                }

                else -> result.append(escaped)
            }
            index += 2
        }
        return result.toString()
    }

    private val quotedStringPattern = Regex("\"((?:\\\\.|[^\"\\\\])*)\"")
}
