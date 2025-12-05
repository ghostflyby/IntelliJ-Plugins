/*
 * Copyright (c) 2025 ghostflyby
 * SPDX-FileCopyrightText: 2025 ghostflyby
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

package dev.ghostflyby.spotless

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.extensions.ExtensionPointListener
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.extensions.PluginDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.toNioPathOrNull
import dev.ghostflyby.spotless.SpotlessFormatResult.*
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.absolutePathString
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds


internal const val spotlessNotificationGroupId = "Spotless Notifications"

@Service(Service.Level.APP)
public class Spotless(private val scope: CoroutineScope) : Disposable.Default {
    public companion object {
        @JvmField
        public val EP_NAME: ExtensionPointName<SpotlessDaemonProvider> =
            ExtensionPointName.create<SpotlessDaemonProvider>("dev.ghostflyby.spotless.spotlessDaemonProvider")
    }

    init {
        EP_NAME.forEachExtensionSafe(::addDisposable)
        EP_NAME.addExtensionPointListener(
            scope,
            object : ExtensionPointListener<SpotlessDaemonProvider> {
                override fun extensionAdded(extension: SpotlessDaemonProvider, pluginDescriptor: PluginDescriptor) {
                    addDisposable(extension)
                }

                override fun extensionRemoved(extension: SpotlessDaemonProvider, pluginDescriptor: PluginDescriptor) {
                    Disposer.dispose(extension)
                }
            },
        )
    }

    private fun addDisposable(disposable: Disposable): Unit = Disposer.register(this, disposable)


    internal val http = HttpClient(CIO)

    private val hosts = ConcurrentHashMap<String, SpotlessDaemonHost>()

    private suspend fun SpotlessDaemonProvider.getDaemon(
        project: Project,
        externalProject: Path,
    ): SpotlessDaemonHost {
        val pathString = externalProject.normalize().absolutePathString()
        return hosts.getOrPut(pathString) {
            val host = startDaemon(project, externalProject)
            Disposer.register(this) {
                hosts.remove(pathString)?.let { Disposer.dispose(it) }
            }
            Disposer.register(host) {
                hosts.remove(pathString)
            }
            host
        }
    }

    public suspend fun format(
        project: Project,
        virtualFile: VirtualFile,
        content: CharSequence,
    ): SpotlessFormatResult = scope.async {
        val filePath = virtualFile.toNioPathOrNull() ?: return@async NotCovered
        val extension = EP_NAME.findFirstSafe { it.isApplicableTo(project) }
        val externalProject = extension?.findExternalProjectPath(project, virtualFile) ?: return@async NotCovered
        val daemon = extension.getDaemon(project, externalProject)
        if (!http.healthCheck(daemon)) {
            return@async Error("Spotless Daemon is not responding")
        }
        http.format(daemon, filePath, content)
    }.await()

    public suspend fun canFormat(project: Project, virtualFile: VirtualFile): Boolean =
        format(project, virtualFile, "") == Clean

    public fun canFormatSync(
        project: Project,
        virtualFile: VirtualFile,
        timeout: Duration = 500.milliseconds,
    ): Boolean = runBlocking {
        withTimeoutOrNull(timeout) {
            canFormat(project, virtualFile)
        } ?: false
    }

    override fun dispose() {
        http.close()
        hosts.forEach { (_, host) -> Disposer.dispose(host) }
    }

}

public sealed interface SpotlessFormatResult {
    /**
     * Formatted successfully with the file on disk untouched
     * @property content The formatted output
     */
    public data class Dirty(val content: String) : SpotlessFormatResult

    /**
     * Untouched as already formatted
     */
    public object Clean : SpotlessFormatResult

    /**
     * Not covered by Spotless, either no formater for the filetype or path pattern not included
     */
    public object NotCovered : SpotlessFormatResult

    /**
     * Error occurred during formatting, see `message` for details
     */
    public data class Error(val message: String) : SpotlessFormatResult


}

private suspend fun HttpClient.healthCheck(
    spotlessHost: SpotlessDaemonHost,
): Boolean =
    runCatching {

        get("/") {
            when (spotlessHost) {
                is SpotlessDaemonHost.Localhost -> host = "localhost:${spotlessHost.port}"
                is SpotlessDaemonHost.Unix -> unixSocket(spotlessHost.path.toString())
            }
            url {
                protocol = URLProtocol.HTTP
            }
        }
    }.map { response ->
        response.status == HttpStatusCode.OK
    }.getOrElse { false }


private suspend fun HttpClient.format(
    spotlessHost: SpotlessDaemonHost,
    path: Path,
    content: CharSequence,
): SpotlessFormatResult {
    val response = post("/") {
        when (spotlessHost) {
            is SpotlessDaemonHost.Localhost -> host = "localhost:${spotlessHost.port}"
            is SpotlessDaemonHost.Unix -> unixSocket(spotlessHost.path.toString())
        }
        url {
            protocol = URLProtocol.HTTP
        }
        parameter("path", path.normalize().absolutePathString())
        if (content.isEmpty()) {
            parameter("dryrun", null)
        }
        contentType(ContentType.Text.Plain)
        setBody(content)
    }
    return when (response.status) {
        HttpStatusCode.OK -> {
            val formatted = response.bodyAsText()
            if (formatted.isEmpty()) {
                Clean
            } else {
                Dirty(formatted)
            }
        }

        HttpStatusCode.NotFound -> NotCovered
        HttpStatusCode.InternalServerError -> {
            val message = response.bodyAsText()
            Error(message)
        }

        else -> Error("Unexpected response status: ${response.status}\n${response.bodyAsText()}")
    }
}