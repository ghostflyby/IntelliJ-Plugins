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

import com.intellij.execution.ExecutionListener
import com.intellij.execution.process.*
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.lang.javascript.buildTools.npm.rc.NpmCommand
import com.intellij.lang.javascript.buildTools.npm.rc.NpmRunConfiguration
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.io.URLUtil
import dev.ghostflyby.vitepress.PluginDisposable
import kotlinx.datetime.Clock
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

internal class PreviewNpmListener : ExecutionListener {
    override fun processStarted(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
        val profile = env.runProfile as? NpmRunConfiguration ?: return
        val runSettings = profile.runSettings
        if (runSettings.command != NpmCommand.RUN_SCRIPT) return
        val previewRoots =
            env.project
                .service<VitePressPackageJsonScriptIndex>()
                .findPreviewRoots(runSettings.packageJsonSystemIndependentPath, runSettings.scriptNames)
        if (previewRoots.isEmpty()) return

        val store = env.project.service<VitePressPreviewUrlStore>()
        val ownerId = System.identityHashCode(handler)
        val decoder = AnsiEscapeDecoder()
        val disposable = Disposer.newDisposable(PluginDisposable)

        handler.addProcessListener(
            object : ProcessListener {
                private val textBuilder = StringBuilder()
                private val startAt = Clock.System.now()
                private var urlCaptured: Boolean = false

                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    if (urlCaptured) return
                    if (!ProcessOutputType.isStdout(outputType)) return
                    if ((Clock.System.now() - startAt) > URL_CAPTURE_WINDOW) return
                    decoder.escapeText(event.text, outputType) { text, _ ->
                        textBuilder.append(text)
                    }
                    if (textBuilder.length > OUTPUT_BUFFER_LIMIT) {
                        textBuilder.delete(0, textBuilder.length - OUTPUT_BUFFER_RETAIN)
                    }

                    val captured = extractFirstHttpUrl(textBuilder) ?: return
                    previewRoots.forEach { root ->
                        store.put(root, captured, ownerId)
                    }
                    urlCaptured = true
                }

                override fun processTerminated(event: ProcessEvent) {
                    previewRoots.forEach { root ->
                        store.removeOwnedBy(root, ownerId)
                    }
                    Disposer.dispose(disposable)
                }
            },
            disposable,
        )
    }
}

@Service(Service.Level.PROJECT)
internal class VitePressPreviewUrlStore {
    private data class StoredPreview(val baseUrl: URI, val ownerId: Int)

    private val rootDirectoryToBaseUrl = ConcurrentHashMap<VirtualFile, StoredPreview>()

    internal fun put(root: VirtualFile, baseUrl: URI, ownerId: Int) {
        if (!root.isValid) return
        rootDirectoryToBaseUrl[root] = StoredPreview(baseUrl, ownerId)
    }

    internal fun get(root: VirtualFile): URI? {
        if (!root.isValid) return null
        return rootDirectoryToBaseUrl[root]?.baseUrl
    }

    internal fun removeOwnedBy(root: VirtualFile, ownerId: Int) {
        rootDirectoryToBaseUrl.computeIfPresent(root) { _, stored ->
            if (stored.ownerId == ownerId) null else stored
        }
    }
}

private fun extractFirstHttpUrl(buffer: StringBuilder): URI? {
    val urlString =
        buffer.lineSequence()
            .filter { line -> URLUtil.canContainUrl(line) }
            .firstNotNullOfOrNull { line ->
                val matcher = URLUtil.URL_PATTERN_OPTIMIZED.matcher(line)
                if (matcher.find()) line.substring(matcher.start(), matcher.end()) else null
            } ?: return null
    val url = runCatching { URI(urlString) }.getOrNull() ?: return null
    val scheme = url.scheme?.lowercase() ?: return null
    if (scheme != "http" && scheme != "https") return null
    return normalizeBaseUrl(url)
}

private fun normalizeBaseUrl(url: URI): URI {
    val path = url.path
    val normalizedPath =
        when {
            path.isNullOrEmpty() -> "/"
            path.endsWith("/") -> path
            else -> "$path/"
        }
    return runCatching {
        URI(url.scheme, url.userInfo, url.host, url.port, normalizedPath, url.query, url.fragment)
    }.getOrDefault(url)
}

private val URL_CAPTURE_WINDOW = 7.seconds
private const val OUTPUT_BUFFER_LIMIT: Int = 8_192
private const val OUTPUT_BUFFER_RETAIN: Int = 4_096
