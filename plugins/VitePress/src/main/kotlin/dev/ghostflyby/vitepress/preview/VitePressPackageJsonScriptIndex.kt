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

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.readText
import com.intellij.openapi.vfs.toNioPathOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Path
import java.util.*

@Service(Service.Level.PROJECT)
internal class VitePressPackageJsonScriptIndex(private val project: Project) : Disposable {
    private val virtualFileManager = service<VirtualFileManager>()
    private val json = Json { ignoreUnknownKeys = true }
    private val cachedFiles = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<VirtualFile, Boolean>()),
    )

    override fun dispose() {
        val snapshot = synchronized(cachedFiles) { cachedFiles.toList() }
        snapshot.forEach { file ->
            file.putUserData(SCRIPTS_CACHE_KEY, null)
        }
        synchronized(cachedFiles) { cachedFiles.clear() }
    }

    internal fun findPreviewRoots(packageJsonPath: Path, scriptNames: List<String>): Set<Path> {

        if (scriptNames.isEmpty()) return emptySet()
        val normalizedPath = packageJsonPath.normalize()
        val packageJsonFile = findFileByPath(normalizedPath) ?: return emptySet()
        val scriptCommands = parseScriptsFromFile(packageJsonFile)
        if (scriptCommands.isEmpty()) return emptySet()
        val packageJsonDirectory = packageJsonFile.parent ?: return emptySet()

        val roots = linkedSetOf<Path>()
        scriptNames.forEach { scriptName ->
            val command = scriptCommands[scriptName] ?: return@forEach
            extractVitePressRootPaths(command, packageJsonDirectory)
                .asSequence()
                .mapNotNull { path -> resolvePathToVitePressRoot(path) }
                .forEach { root -> roots.add(root) }
        }
        return roots
    }

    private fun findFileByPath(path: Path): VirtualFile? {
        return runCatching {
            virtualFileManager.findFileByNioPath(path)
        }.getOrNull()
    }

    private fun resolvePathToVitePressRoot(path: Path): Path? {

        val root = runCatching {
            virtualFileManager.findFileByNioPath(path)
        }.getOrNull() ?: return null
        val normalizedRoot = normalizeToVitePressRoot(root) ?: return null
        return normalizedRoot.toNioPathOrNull()
    }

    private fun parseScriptsFromFile(file: VirtualFile): Map<String, String> {
        if (!file.isValid) return emptyMap()
        val stamp = file.modificationStamp
        val cached = file.getUserData(SCRIPTS_CACHE_KEY)
        if (cached != null && cached.stamp == stamp) return cached.scripts

        val scripts = readScriptsFromFile(file)
        file.putUserData(SCRIPTS_CACHE_KEY, PackageJsonScriptsCache(stamp, scripts))
        cachedFiles.add(file)
        return scripts
    }

    private fun readScriptsFromFile(file: VirtualFile): Map<String, String> {
        val text = file.readText()
        val payload = runCatching {
            json.decodeFromString<PackageJsonScriptsPayload>(text)
        }.getOrNull() ?: return emptyMap()
        return payload.scripts
    }
}


@Serializable
private data class PackageJsonScriptsPayload(
    val scripts: Map<String, String> = emptyMap(),
)

private data class PackageJsonScriptsCache(
    val stamp: Long,
    val scripts: Map<String, String>,
)

private fun extractVitePressRootPaths(command: String, packageJsonDirectory: VirtualFile): Set<Path> {
    val packageJsonDirectoryPath = packageJsonDirectory.toNioPathOrNull() ?: return emptySet()
    val resolvedRoots = linkedSetOf<Path>()

    splitCommandSegments(command).forEach { segment ->
        val tokens = tokenizeCommandSegment(segment)
        if (tokens.isEmpty()) return@forEach

        val invocation = parseVitePressInvocation(tokens) ?: return@forEach
        val rootPath = invocation.rootDirectoryArg?.let { arg ->
            resolvePathAgainstBase(packageJsonDirectoryPath, arg)
        } ?: packageJsonDirectoryPath
        resolvedRoots.add(rootPath.normalize())
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

private fun resolvePathAgainstBase(basePath: Path, pathText: String): Path? {
    val path = runCatching { Path.of(pathText) }.getOrNull() ?: return null
    return if (path.isAbsolute) path.normalize() else basePath.resolve(path).normalize()
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
private val SCRIPTS_CACHE_KEY = Key.create<PackageJsonScriptsCache>("vitepress.packageJsonScriptsCache")