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
import io.ktor.client.engine.okhttp.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.net.Socket
import java.net.UnixDomainSocketAddress
import java.nio.channels.SocketChannel
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import javax.net.SocketFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds


internal const val spotlessNotificationGroupId = "Spotless Notifications"

@Service
public class Spotless(private val scope: CoroutineScope) : Disposable.Default {
    public companion object {
        @JvmStatic
        public val EP_NAME: ExtensionPointName<SpotlessExtension> =
            ExtensionPointName.create<SpotlessExtension>("dev.ghostflyby.spotless.spotlessDaemonProvider")
    }

    init {
        EP_NAME.forEachExtensionSafe(::addDisposable)
        EP_NAME.addExtensionPointListener(
            scope,
            object : ExtensionPointListener<SpotlessExtension> {
                override fun extensionAdded(extension: SpotlessExtension, pluginDescriptor: PluginDescriptor) {
                    addDisposable(extension)
                }

                override fun extensionRemoved(extension: SpotlessExtension, pluginDescriptor: PluginDescriptor) {
                    Disposer.dispose(extension)
                }
            },
        )
    }

    private fun addDisposable(disposable: Disposable): Unit = Disposer.register(this, disposable)


    private val https = ConcurrentHashMap<SpotlessDaemonHost, HttpClient>()
    private fun http(path: SpotlessDaemonHost): HttpClient {
        return https.computeIfAbsent(path) {
            HttpClient(OkHttp) {
                engine {
                    config {
                        if (path is SpotlessDaemonHost.Unix) socketFactory(
                            UnixSocketFactory(
                                UnixDomainSocketAddress.of(
                                    path.path,
                                ),
                            ),
                        )
                    }
                }
            }
        }
    }

    public suspend fun format(
        project: Project,
        virtualFile: VirtualFile,
        content: CharSequence,
    ): SpotlessFormatResult = scope.async {
        val externalProject = virtualFile.findExternalRoot(project) ?: return@async NotCovered
        val path = virtualFile.toNioPathOrNull() ?: return@async NotCovered
        val daemon = findDaemon(project, externalProject) ?: return@async Clean
        http(daemon).format(daemon, path, content)
    }.await()

    public suspend fun canFormat(project: Project, virtualFile: VirtualFile): Boolean = scope.async {
        val external = virtualFile.findExternalRoot(project) ?: return@async false
        if (!isApplicableTo(project, external)) {
            return@async false
        }
        val filePath = virtualFile.toNioPathOrNull() ?: return@async false
        val daemon = findDaemon(project, external) ?: return@async false
        val result = http(daemon).format(daemon, filePath, "", dryrun = true)
        result == Clean
    }.await()

    public fun canFormatSync(
        project: Project,
        virtualFile: VirtualFile,
        timeout: Duration = 200.milliseconds,
    ): Boolean = runBlocking {
        withTimeoutOrNull(timeout) {
            canFormat(project, virtualFile)
        } ?: false
    }

    private fun isApplicableTo(project: Project, externalProject: Path?): Boolean {
        return EP_NAME.findFirstSafe { it.isApplicableTo(project, externalProject) } != null
    }


    private suspend fun findDaemon(
        project: Project,
        externalProject: Path,
    ): SpotlessDaemonHost? {
        val ext = EP_NAME.findFirstSafe { it.isApplicableTo(project, externalProject) } ?: return null
        return ext.getDaemon(project, externalProject)
    }

    private fun VirtualFile.findExternalRoot(project: Project): Path? {
        val ext = EP_NAME.findFirstSafe { it.isApplicableTo(project, null) } ?: return null
        return ext.findExternalProjectPath(project, this)
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

private suspend fun HttpClient.format(
    spotlessHost: SpotlessDaemonHost,
    path: Path,
    content: CharSequence,
    dryrun: Boolean = false,
): SpotlessFormatResult {
    val response = post("/") {
        when (spotlessHost) {
            is SpotlessDaemonHost.Localhost -> host = "localhost:${spotlessHost.port}"
            is SpotlessDaemonHost.Unix -> Unit// unixSocket(spotlessHost.path.toString())
        }
        url {
            protocol = URLProtocol.HTTP
        }
        parameter("path", path)
        if (dryrun) {
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

internal class UnixSocketFactory(private val socketAddress: UnixDomainSocketAddress) : SocketFactory() {

    override fun createSocket(): Socket? {
        return SocketChannel.open(socketAddress).socket()
    }


    override fun createSocket(p0: String?, p1: Int): Socket? = createSocket()

    override fun createSocket(
        p0: String?,
        p1: Int,
        p2: InetAddress?,
        p3: Int,
    ): Socket? = createSocket()

    override fun createSocket(p0: InetAddress?, p1: Int): Socket? = createSocket()

    override fun createSocket(
        p0: InetAddress?,
        p1: Int,
        p2: InetAddress?,
        p3: Int,
    ): Socket? = createSocket()
}