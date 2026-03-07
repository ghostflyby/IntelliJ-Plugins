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
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.readText
import com.intellij.openapi.vfs.toNioPathOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Path
import java.util.*

@Service
internal class VitePressPackageJsonScriptIndex : Disposable {
    private val virtualFileManager get() = service<VirtualFileManager>()
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
        val packageJsonDirectoryPath = packageJsonDirectory.toNioPathOrNull() ?: return emptySet()

        val roots = linkedSetOf<Path>()
        scriptNames.forEach { scriptName ->
            val command = scriptCommands[scriptName] ?: return@forEach
            extractVitePressRootPaths(command, packageJsonDirectoryPath)
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

internal fun extractVitePressRootPaths(command: String, packageJsonDirectoryPath: Path): Set<Path> {
    val resolvedRoots = linkedSetOf<Path>()
    var workingDirectory = packageJsonDirectoryPath

    splitCommandSegments(command).forEach { segment ->
        val tokens = tokenizeCommandSegment(segment)
        if (tokens.isEmpty()) return@forEach

        parseCdInvocation(tokens)?.let { cd ->
            workingDirectory = resolvePathAgainstBase(workingDirectory, cd.targetDirectory) ?: workingDirectory
            return@forEach
        }

        val invocation = parseVitePressInvocation(tokens) ?: return@forEach
        val rootPath = invocation.rootDirectoryArg?.let { arg ->
            resolvePathAgainstBase(workingDirectory, arg)
        } ?: workingDirectory
        resolvedRoots.add(rootPath.normalize())
    }
    return resolvedRoots
}

private data class ParsedCdInvocation(
    val targetDirectory: String,
)

private data class ParsedVitePressInvocation(
    val rootDirectoryArg: String?,
)

private fun parseCdInvocation(tokens: List<String>): ParsedCdInvocation? {
    if (!tokens.firstOrNull().equals("cd", ignoreCase = true)) return null
    val targetDirectory =
        tokens.asSequence()
            .drop(1)
            .firstOrNull { token -> token != "--" && !token.startsWith("-") }
            ?: return null
    return ParsedCdInvocation(targetDirectory)
}

private fun parseVitePressInvocation(tokens: List<String>): ParsedVitePressInvocation? {
    val vitepressIndex = tokens.indexOfFirst { token -> isVitePressExecutableToken(token) }
    if (vitepressIndex < 0) return null

    var argumentIndex = vitepressIndex + 1
    if (tokens.getOrNull(argumentIndex) == "--") {
        argumentIndex++
    }

    val command =
        when (val explicitCommand = tokens.getOrNull(argumentIndex)?.lowercase()) {
            null -> VITEPRESS_DEV_COMMAND
            in VITEPRESS_COMMANDS -> {
                argumentIndex++
                normalizeVitePressCommand(explicitCommand)
            }

            in VITEPRESS_NON_PREVIEW_COMMANDS -> return null
            else -> VITEPRESS_DEV_COMMAND
        }

    val rootDirectoryArg = parseVitePressRootArgument(tokens, argumentIndex, command)
    return ParsedVitePressInvocation(rootDirectoryArg)
}

private fun parseVitePressRootArgument(tokens: List<String>, startIndex: Int, command: String): String? {
    val optionValueKinds = VITEPRESS_OPTION_VALUE_KINDS[command] ?: emptyMap()
    var index = startIndex

    while (index < tokens.size) {
        val token = tokens[index]
        when {
            token == "--" -> {
                index++
                break
            }

            !token.startsWith("-") -> return token
            else -> {
                val optionName = token.substringBefore('=')
                val valueKind = optionValueKinds[optionName] ?: VitePressOptionValueKind.NONE
                val hasInlineValue = token.contains('=')

                index += when {
                    hasInlineValue -> 1
                    valueKind == VitePressOptionValueKind.REQUIRED && index + 1 < tokens.size -> 2
                    valueKind == VitePressOptionValueKind.OPTIONAL &&
                            tokens.getOrNull(index + 1)?.startsWith("-") == false -> 2

                    else -> 1
                }
            }
        }
    }

    while (index < tokens.size) {
        val token = tokens[index]
        if (!token.startsWith("-")) return token
        index++
    }
    return null
}

private fun normalizeVitePressCommand(command: String): String {
    return when (command) {
        VITEPRESS_SERVE_COMMAND -> VITEPRESS_PREVIEW_COMMAND
        else -> command
    }
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

private enum class VitePressOptionValueKind {
    NONE,
    REQUIRED,
    OPTIONAL,
}

private val VITEPRESS_COMMANDS = setOf(VITEPRESS_DEV_COMMAND, VITEPRESS_PREVIEW_COMMAND, VITEPRESS_SERVE_COMMAND)
private val VITEPRESS_NON_PREVIEW_COMMANDS = setOf("build")
private val VITEPRESS_OPTION_VALUE_KINDS = mapOf(
    VITEPRESS_DEV_COMMAND to mapOf(
        "--open" to VitePressOptionValueKind.OPTIONAL,
        "--port" to VitePressOptionValueKind.REQUIRED,
        "--base" to VitePressOptionValueKind.REQUIRED,
        "--cors" to VitePressOptionValueKind.NONE,
        "--strictPort" to VitePressOptionValueKind.NONE,
        "--force" to VitePressOptionValueKind.NONE,
    ),
    VITEPRESS_PREVIEW_COMMAND to mapOf(
        "--base" to VitePressOptionValueKind.REQUIRED,
        "--port" to VitePressOptionValueKind.REQUIRED,
    ),
)
private const val VITEPRESS_DEV_COMMAND: String = "dev"
private const val VITEPRESS_PREVIEW_COMMAND: String = "preview"
private const val VITEPRESS_SERVE_COMMAND: String = "serve"
private const val VITEPRESS_CONFIG_DIRECTORY: String = ".vitepress"
private val SCRIPTS_CACHE_KEY = Key.create<PackageJsonScriptsCache>("vitepress.packageJsonScriptsCache")
