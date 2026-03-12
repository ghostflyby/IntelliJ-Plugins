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

package dev.ghostflyby.mill.script

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import dev.ghostflyby.mill.MillConstants
import dev.ghostflyby.mill.MillSettings
import dev.ghostflyby.mill.command.MillCommandLineUtil
import dev.ghostflyby.mill.project.MillProjectResolverSupport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

internal data class MillBuildScriptModel(
    val projectRoot: Path,
    val sourceRoots: List<Path>,
    val resolveBinaryClasspath: List<Path>,
    val displayBinaryClasspath: List<Path>,
    val scalaVersion: String?,
    val scalaCompilerClasspath: List<Path>,
    val javaHomePath: String?,
) {
    val resolveRoots: List<Path>
        get() = (sourceRoots + resolveBinaryClasspath).distinct()
}

internal object MillBuildScriptSupport {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    internal fun isBuildScriptFile(file: VirtualFile): Boolean {
        return !file.isDirectory && isBuildScriptFileName(file.name)
    }

    internal fun isBuildScriptFileName(fileName: String): Boolean {
        return fileName == MillConstants.buildScriptFileName
    }

    internal fun outputDirectory(projectRoot: Path): Path {
        return projectRoot.resolve(MillConstants.buildScriptOutputDirectory).normalize()
    }

    internal fun buildScriptFile(projectRoot: Path): Path {
        return projectRoot.resolve(MillConstants.buildScriptFileName).normalize()
    }

    internal fun generatedSourcesFile(projectRoot: Path): Path {
        return outputDirectory(projectRoot).resolve(MillConstants.buildScriptGeneratedSourcesFileName)
    }

    internal fun compileClasspathFile(projectRoot: Path): Path {
        return outputDirectory(projectRoot).resolve(MillConstants.buildScriptClasspathFileName)
    }

    internal fun scalaVersionFile(projectRoot: Path): Path {
        return outputDirectory(projectRoot).resolve("scalaVersion.json")
    }

    internal fun scalaCompilerClasspathFile(projectRoot: Path): Path {
        return outputDirectory(projectRoot).resolve("scalaCompilerClasspath.json")
    }

    internal fun javaHomeFile(projectRoot: Path): Path {
        return outputDirectory(projectRoot).resolve("javaHome.json")
    }

    internal fun loadModel(projectRoot: Path): MillBuildScriptModel? {
        val sourceRoots = readGeneratedSourceRoots(generatedSourcesFile(projectRoot))
        val resolveBinaryClasspath = readBinaryClasspath(compileClasspathFile(projectRoot))
        val scalaCompilerClasspath = readValuePathList(scalaCompilerClasspathFile(projectRoot))
        val displayBinaryClasspath = filterDisplayBinaryClasspath(projectRoot, resolveBinaryClasspath)
        val scalaVersion = readValueString(scalaVersionFile(projectRoot))
        val javaHomePath = readValueString(javaHomeFile(projectRoot))
        if (sourceRoots.isEmpty() && resolveBinaryClasspath.isEmpty() && scalaCompilerClasspath.isEmpty() && scalaVersion == null) {
            return null
        }
        return MillBuildScriptModel(
            projectRoot = projectRoot,
            sourceRoots = sourceRoots,
            resolveBinaryClasspath = resolveBinaryClasspath,
            displayBinaryClasspath = displayBinaryClasspath,
            scalaVersion = scalaVersion,
            scalaCompilerClasspath = scalaCompilerClasspath,
            javaHomePath = javaHomePath,
        )
    }

    internal fun candidateProjectRoots(project: Project): List<Path> {
        val linkedRoots = MillSettings.getInstance(project).linkedProjectsSettings
            .mapNotNull { settings -> settings.externalProjectPath?.takeIf(String::isNotBlank) }
            .mapNotNull { path -> runCatching { Path.of(path).toAbsolutePath().normalize() }.getOrNull() }
            .distinct()
        if (linkedRoots.isNotEmpty()) {
            return linkedRoots
        }

        val basePath = project.basePath ?: return emptyList()
        val root = runCatching { Path.of(basePath).toAbsolutePath().normalize() }.getOrNull() ?: return emptyList()
        return listOf(root).filter { path -> MillProjectResolverSupport.hasMillConfig(path.toString()) }
    }

    internal fun parseGeneratedSourceRootPaths(output: String): List<Path> {
        val valueObject = runCatching {
            json.parseToJsonElement(output).jsonObject["value"]?.jsonObject
        }.getOrNull() ?: return emptyList()
        val support = valueObject["support"]?.jsonArray.orEmpty().map { element -> element.jsonPrimitive.content }
        val wrapped = valueObject["wrapped"]?.jsonArray.orEmpty().map { element -> element.jsonPrimitive.content }
        return normalizePaths(support + wrapped)
    }

    private fun readGeneratedSourceRoots(path: Path): List<Path> {
        if (!Files.isRegularFile(path)) {
            return emptyList()
        }
        val output = runCatching { Files.readString(path) }.getOrNull() ?: return emptyList()
        return parseGeneratedSourceRootPaths(output).filter(Files::isDirectory)
    }

    private fun readBinaryClasspath(path: Path): List<Path> {
        if (!Files.isRegularFile(path)) {
            return emptyList()
        }
        return readValuePathList(path).ifEmpty {
            val output = runCatching { Files.readString(path) }.getOrNull() ?: return@ifEmpty emptyList()
            normalizePaths(MillCommandLineUtil.parseStringList(output))
                .filter(Files::exists)
        }
    }

    private fun readValuePathList(path: Path): List<Path> {
        return readValueElement(path)?.jsonArray.orEmpty()
            .map { element -> element.jsonPrimitive.content }
            .let(::normalizePaths)
            .filter(Files::exists)
    }

    private fun readValueString(path: Path): String? {
        return readValueElement(path)
            ?.jsonPrimitive
            ?.contentOrNull
            ?.let(MillCommandLineUtil::normalizeOutputValue)
            ?.takeIf(String::isNotBlank)
    }

    private fun readValueElement(path: Path): JsonElement? {
        if (!Files.isRegularFile(path)) {
            return null
        }
        val output = runCatching { Files.readString(path) }.getOrNull() ?: return null
        return runCatching {
            json.parseToJsonElement(output).jsonObject["value"]
        }.getOrNull()
    }

    private fun normalizePaths(values: List<String>): List<Path> {
        return values.asSequence()
            .map(MillCommandLineUtil::normalizeOutputValue)
            .mapNotNull { rawPath -> runCatching { Path.of(rawPath) }.getOrNull() }
            .map(Path::toAbsolutePath)
            .map(Path::normalize)
            .distinct()
            .toList()
    }

    internal fun filterDisplayBinaryClasspath(projectRoot: Path, classpath: List<Path>): List<Path> {
        val outputRoot = outputDirectory(projectRoot)
        return classpath.filterNot { path -> path.startsWith(outputRoot) }
    }
}
