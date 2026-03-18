/*
 * Copyright (c) 2025-2026 ghostflyby
 * SPDX-FileCopyrightText: 2025-2026 ghostflyby
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
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.toNioPathOrNull
import dev.ghostflyby.spotless.SpotlessFormatResult.*
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.*
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.absolutePathString
import kotlin.time.Duration


internal const val spotlessNotificationGroupId = "Spotless Notifications"

internal class SpotlessImpl(private val scope: CoroutineScope) : Spotless, Disposable.Default {
    private data class DaemonEntry(
        val provider: SpotlessDaemonProvider,
        val host: SpotlessDaemonHost,
    )

    private data class FormatTarget(
        val provider: SpotlessDaemonProvider,
        val externalProject: Path,
        val filePath: Path,
    )

    companion object {
        @JvmField
        val EP_NAME: ExtensionPointName<SpotlessDaemonProvider> =
            ExtensionPointName.create("dev.ghostflyby.spotless.spotlessDaemonProvider")
    }

    private val logger = logger<SpotlessImpl>()

    internal var http: HttpClient = HttpClient(CIO)
    private val hosts = ConcurrentHashMap<String, DaemonEntry>()
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal var daemonProviderLookup: (Project) -> SpotlessDaemonProvider? =
        { project -> EP_NAME.findFirstSafe { it.isApplicableTo(project) } }

    private suspend fun SpotlessDaemonProvider.getDaemon(
        project: Project,
        externalProject: Path,
    ): SpotlessDaemonHost {
        val pathString = externalProject.normalize().absolutePathString()
        val current = hosts[pathString]
        if (current != null) {
            if (current.provider === this) {
                return current.host
            }
            if (hosts.remove(pathString, current)) {
                scheduleStop(current, "provider switched")
            }
        }
        val host = startDaemon(project, externalProject)
        val newEntry = DaemonEntry(this, host)
        val raceEntry = hosts.putIfAbsent(pathString, newEntry)
        if (raceEntry != null) {
            scheduleStop(newEntry, "daemon race lost")
            return raceEntry.host
        }
        return host
    }

    override fun releaseDaemon(host: SpotlessDaemonHost) {
        val entries = hosts.entries
            .filter { it.value.host === host }
        entries.forEach { (key, entry) ->
            hosts.remove(key, entry)
        }
        if (entries.isEmpty()) {
            return
        }
        entries.forEach {
            scheduleStop(it.value, "external release")
        }
    }

    private fun scheduleStop(entry: DaemonEntry, reason: String) {
        scope.launch(Dispatchers.IO) {
            stopDaemonEntry(entry, reason)
        }
    }

    private fun resolveFormatTarget(project: Project, virtualFile: VirtualFile): FormatTarget? {
        val filePath = virtualFile.toNioPathOrNull() ?: return null
        val provider = daemonProviderLookup(project) ?: return null
        val externalProject = provider.findExternalProjectPath(project, virtualFile) ?: return null
        return FormatTarget(provider, externalProject, filePath)
    }

    private suspend fun stopDaemonEntry(entry: DaemonEntry, reason: String) {
        val stopFailure = runCatching {
            http.stopDaemon(entry.host)
        }.exceptionOrNull()
        runCatching {
            entry.provider.afterDaemonStopped(entry.host, reason)
        }.onFailure { error ->
            logger.warn("Provider afterDaemonStopped hook failed ($reason): ${entry.host}", error)
        }
        if (stopFailure != null) {
            logger.warn("Failed to stop daemon ($reason): ${entry.host}", stopFailure)
        }
    }

    override suspend fun format(
        project: Project,
        virtualFile: VirtualFile,
        content: CharSequence,
    ): SpotlessFormatResult {
        val target = resolveFormatTarget(project, virtualFile) ?: return NotCovered
        val daemon = target.provider.getDaemon(project, target.externalProject)
        if (!http.healthCheck(daemon)) {
            return Error("Spotless Daemon is not responding")
        }
        return http.format(daemon, target.filePath, content)
    }

    override suspend fun canFormat(project: Project, virtualFile: VirtualFile): Boolean =
        format(project, virtualFile, "") == Clean

    override fun canFormatSync(
        project: Project,
        virtualFile: VirtualFile,
        timeout: Duration,
    ): Boolean = runBlocking {
        withTimeoutOrNull(timeout) {
            canFormat(project, virtualFile)
        } ?: false
    }

    override fun dispose() {
        val entries = hosts.values.toSet()
        hosts.clear()
        cleanupScope.launch {
            entries.forEach { entry ->
                stopDaemonEntry(entry, "service disposed")
            }
            runCatching {
                http.close()
            }.onFailure { error ->
                logger.warn("Failed to close Spotless HTTP client", error)
            }
        }
    }

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

internal suspend fun HttpClient.stopDaemon(
    spotlessHost: SpotlessDaemonHost,
) {
    post("/stop") {
        when (spotlessHost) {
            is SpotlessDaemonHost.Localhost -> host = "localhost:${spotlessHost.port}"
            is SpotlessDaemonHost.Unix -> unixSocket(spotlessHost.path.toString())
        }
        url {
            protocol = URLProtocol.HTTP
        }
    }
}


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
            parameter("dryrun", "")
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
