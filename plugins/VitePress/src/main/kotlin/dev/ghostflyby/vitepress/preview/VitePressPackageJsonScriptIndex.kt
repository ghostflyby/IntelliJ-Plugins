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

package dev.ghostflyby.vitepress.preview

import com.intellij.javascript.nodejs.packageJson.PackageJsonFileManager
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.findPsiFile
import dev.ghostflyby.vitepress.isUnderVitePressRoot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.PROJECT)
internal class VitePressPackageJsonScriptIndex(
    private val project: Project,
    scope: CoroutineScope,
) {
    private sealed class UpdateRequest {
        data object RefreshAll : UpdateRequest()
        data class Reindex(val packageJsonPath: String) : UpdateRequest()
        data class Remove(val packageJsonPath: String) : UpdateRequest()
    }

    private data class ScriptRecord(
        val scriptName: String,
        val command: String,
        val roots: Set<VirtualFile>,
    )

    private data class PackageJsonRecord(
        val scripts: Map<String, ScriptRecord>,
    )

    private val packageJsonPathToRecord = ConcurrentHashMap<String, PackageJsonRecord>()
    private val virtualFileManager = service<VirtualFileManager>()
    private val updateRequests =
        MutableSharedFlow<UpdateRequest>(
            extraBufferCapacity = 256,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    init {
        scope.launch {
            updateRequests.collect { request ->
                when (request) {
                    is UpdateRequest.RefreshAll -> processFullRefresh()
                    is UpdateRequest.Reindex -> processReindex(request.packageJsonPath)
                    is UpdateRequest.Remove -> packageJsonPathToRecord.remove(request.packageJsonPath)
                }
            }
        }

    }


    internal fun refreshAll() {
        enqueue(UpdateRequest.RefreshAll)
    }

    internal fun findPreviewRoots(packageJsonPath: String, scriptNames: List<String>): Set<VirtualFile> {
        if (scriptNames.isEmpty()) return emptySet()
        ensureIndexed(packageJsonPath)
        val record = packageJsonPathToRecord[packageJsonPath] ?: return emptySet()
        return scriptNames.asSequence()
            .mapNotNull { scriptName -> record.scripts[scriptName] }
            .flatMap { scriptRecord -> scriptRecord.roots.asSequence() }
            .filter { root -> root.isValid }
            .toCollection(linkedSetOf())
    }

    internal fun onPackageJsonChanged(event: PackageJsonFileManager.PackageJsonChangeEvent) {
        val packageJsonFile = event.file
        when (event.type) {
            PackageJsonFileManager.PackageJsonEventType.CREATED,
            PackageJsonFileManager.PackageJsonEventType.DOCUMENT_CONTENT_CHANGED,
            PackageJsonFileManager.PackageJsonEventType.VIRTUAL_FILE_CONTENT_CHANGED,
                -> {
                enqueue(UpdateRequest.Reindex(packageJsonFile.path))
            }

            PackageJsonFileManager.PackageJsonEventType.DELETED -> {
                enqueue(UpdateRequest.Remove(packageJsonFile.path))
            }
        }
    }

    private fun ensureIndexed(packageJsonPath: String) {
        if (packageJsonPathToRecord.containsKey(packageJsonPath)) return
        enqueue(UpdateRequest.Reindex(packageJsonPath))
    }

    private fun enqueue(request: UpdateRequest) {
        updateRequests.tryEmit(request)
    }

    private suspend fun processFullRefresh() {
        val packageJsonFiles = readAction {
            PackageJsonFileManager.getInstance(project).validPackageJsonFiles.toList()
        }
        val validPaths = packageJsonFiles.mapTo(hashSetOf()) { it.path }
        packageJsonPathToRecord.keys.retainAll(validPaths)
        packageJsonFiles.forEach { packageJsonFile ->
            indexPackageJson(packageJsonFile)
        }
    }

    private suspend fun processReindex(packageJsonPath: String) {
        val packageJsonFile = findFileByPath(packageJsonPath) ?: run {
            packageJsonPathToRecord.remove(packageJsonPath)
            return
        }
        indexPackageJson(packageJsonFile)
    }

    private suspend fun indexPackageJson(packageJsonFile: VirtualFile) {
        if (!packageJsonFile.isValid) {
            packageJsonPathToRecord.remove(packageJsonFile.path)
            return
        }
        val scriptCommands = parseScriptsWithPsi(project, packageJsonFile)
        if (scriptCommands.isEmpty()) {
            packageJsonPathToRecord.remove(packageJsonFile.path)
            return
        }

        val packageJsonDirectory = packageJsonFile.parent ?: run {
            packageJsonPathToRecord.remove(packageJsonFile.path)
            return
        }

        val scriptRecords = buildMap {
            scriptCommands.forEach { (scriptName, command) ->
                val roots =
                    extractVitePressRootPaths(command, packageJsonDirectory)
                        .asSequence()
                        .mapNotNull { path -> resolvePathToVitePressRoot(path) }
                        .toCollection(linkedSetOf())
                if (roots.isEmpty()) return@forEach
                put(
                    scriptName,
                    ScriptRecord(
                        scriptName = scriptName,
                        command = command,
                        roots = roots,
                    ),
                )
            }
        }

        if (scriptRecords.isEmpty()) {
            packageJsonPathToRecord.remove(packageJsonFile.path)
            return
        }
        packageJsonPathToRecord[packageJsonFile.path] = PackageJsonRecord(scriptRecords)
    }

    private fun findFileByPath(path: String): VirtualFile? {
        return runCatching {
            virtualFileManager.findFileByNioPath(Path.of(FileUtil.toSystemDependentName(path)))
        }.getOrNull()
    }

    private fun resolvePathToVitePressRoot(path: String): VirtualFile? {
        val root = runCatching {
            virtualFileManager.findFileByNioPath(Path.of(FileUtil.toSystemDependentName(path)))
        }.getOrNull() ?: return null
        return normalizeToVitePressRoot(root)
    }
}

internal class VitePressPackageJsonScriptIndexListener(private val project: Project) :
    PackageJsonFileManager.PackageJsonChangesListener {
    override fun onChange(events: List<PackageJsonFileManager.PackageJsonChangeEvent>) {
        val index = project.service<VitePressPackageJsonScriptIndex>()
        events.forEach {
            index.onPackageJsonChanged(it)
        }
        index.refreshAll()
    }
}

private suspend fun parseScriptsWithPsi(project: Project, file: VirtualFile): Map<String, String> {
    return readAction {
        val psiFile = file.findPsiFile(project) as? JsonFile ?: return@readAction emptyMap()
        val rootObject = psiFile.topLevelValue as? JsonObject ?: return@readAction emptyMap()
        val scriptsObject =
            rootObject.findProperty("scripts")?.value as? JsonObject ?: return@readAction emptyMap()
        buildMap {
            scriptsObject.propertyList.forEach { property ->
                val command = (property.value as? JsonStringLiteral)?.value ?: return@forEach
                put(property.name, command)
            }
        }
    }
}

private fun extractVitePressRootPaths(command: String, packageJsonDirectory: VirtualFile): Set<String> {
    val packageJsonDirectoryPath = safePathOf(packageJsonDirectory.path) ?: return emptySet()
    val resolvedRoots = linkedSetOf<String>()
    var currentWorkingDirectory = packageJsonDirectoryPath

    splitCommandSegments(command).forEach { segment ->
        val tokens = tokenizeCommandSegment(segment)
        if (tokens.isEmpty()) return@forEach

        if (tokens.first().equals("cd", ignoreCase = true) && tokens.size >= 2) {
            resolvePathFromWorkingDirectory(currentWorkingDirectory, tokens[1])?.let { resolved ->
                currentWorkingDirectory = resolved
            }
            return@forEach
        }

        val invocation = parseVitePressInvocation(tokens) ?: return@forEach
        val rootPath =
            invocation.rootDirectoryArg?.let { arg ->
                resolvePathFromWorkingDirectory(currentWorkingDirectory, arg)
            } ?: currentWorkingDirectory

        resolvedRoots += FileUtil.toSystemIndependentName(rootPath.normalize().toString())
    }
    return resolvedRoots
}

private data class ParsedVitePressInvocation(
    val rootDirectoryArg: String?,
)

private fun parseVitePressInvocation(tokens: List<String>): ParsedVitePressInvocation? {
    val vitepressIndex = tokens.indexOfFirst { token -> isVitePressExecutableToken(token) }
    if (vitepressIndex < 0) return null

    var subcommandIndex = vitepressIndex + 1
    if (tokens.getOrNull(subcommandIndex) == "--") {
        subcommandIndex++
    }

    val subcommand = tokens.getOrNull(subcommandIndex)?.lowercase() ?: return null
    if (subcommand !in VITEPRESS_COMMANDS) return null

    val rootDirectoryArg =
        tokens.asSequence()
            .drop(subcommandIndex + 1)
            .firstOrNull { token -> !token.startsWith("-") }
    return ParsedVitePressInvocation(rootDirectoryArg)
}

private fun isVitePressExecutableToken(token: String): Boolean {
    val binaryName = token.substringAfterLast('/').substringAfterLast('\\')
    return binaryName.equals("vitepress", ignoreCase = true)
}

private fun resolvePathFromWorkingDirectory(workingDirectory: Path, pathText: String): Path? {
    val path = safePathOf(pathText) ?: return null
    return if (path.isAbsolute) path.normalize() else workingDirectory.resolve(path).normalize()
}

private fun safePathOf(pathText: String): Path? {
    return try {
        Path.of(FileUtil.toSystemDependentName(pathText))
    } catch (_: InvalidPathException) {
        null
    }
}

private fun splitCommandSegments(command: String): List<String> {
    val segments = mutableListOf<String>()
    val current = StringBuilder()
    var quote: Char? = null
    var escaped = false
    var index = 0

    fun flush() {
        val segment = current.toString().trim()
        if (segment.isNotEmpty()) segments += segment
        current.setLength(0)
    }

    while (index < command.length) {
        val ch = command[index]
        if (escaped) {
            current.append(ch)
            escaped = false
            index++
            continue
        }

        if (quote != null) {
            if (ch == quote) quote = null
            if (ch == '\\' && quote == '"') escaped = true
            current.append(ch)
            index++
            continue
        }

        when (ch) {
            '\'', '"' -> {
                quote = ch
                current.append(ch)
                index++
            }

            ';' -> {
                flush()
                index++
            }

            '&' if index + 1 < command.length && command[index + 1] == '&' -> {
                flush()
                index += 2
            }

            '|' if index + 1 < command.length && command[index + 1] == '|' -> {
                flush()
                index += 2
            }

            '\\' -> {
                escaped = true
                current.append(ch)
                index++
            }
            else -> {
                current.append(ch)
                index++
            }
        }
    }
    flush()
    return segments
}

private fun tokenizeCommandSegment(segment: String): List<String> {
    val tokens = mutableListOf<String>()
    val current = StringBuilder()
    var quote: Char? = null
    var escaped = false

    fun flush() {
        if (current.isEmpty()) return
        tokens += current.toString()
        current.setLength(0)
    }

    segment.forEach { ch ->
        if (escaped) {
            current.append(ch)
            escaped = false
            return@forEach
        }

        if (quote != null) {
            if (ch == quote) {
                quote = null
                return@forEach
            }
            if (ch == '\\' && quote == '"') {
                escaped = true
                return@forEach
            }
            current.append(ch)
            return@forEach
        }

        when {
            ch.isWhitespace() -> flush()
            ch == '\'' || ch == '"' -> quote = ch
            ch == '\\' -> escaped = true
            else -> current.append(ch)
        }
    }
    flush()
    return tokens
}

private fun normalizeToVitePressRoot(file: VirtualFile): VirtualFile? {
    file.isUnderVitePressRoot()
    if (!file.isValid) return null
    if (file.isDirectory) {
        if (file.findChild(VITEPRESS_CONFIG_DIRECTORY)?.isDirectory == true) return file
        if (file.name == VITEPRESS_CONFIG_DIRECTORY) return file.parent
    }

    var current: VirtualFile? = if (file.isDirectory) file else file.parent
    while (current != null) {
        if (current.name == VITEPRESS_CONFIG_DIRECTORY) return current.parent
        current = current.parent
    }
    return null
}

private val VITEPRESS_COMMANDS = setOf("dev", "preview")
private const val VITEPRESS_CONFIG_DIRECTORY: String = ".vitepress"
