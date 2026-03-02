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
import com.intellij.openapi.components.Service
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
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.absolutePathString
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds


internal const val spotlessNotificationGroupId = "Spotless Notifications"

@Service(Service.Level.APP)
public class Spotless(private val scope: CoroutineScope) : Disposable.Default {
    private data class DaemonEntry(
        val provider: SpotlessDaemonProvider,
        val host: SpotlessDaemonHost,
    )

    public companion object {
        @JvmField
        public val EP_NAME: ExtensionPointName<SpotlessDaemonProvider> =
            ExtensionPointName.create("dev.ghostflyby.spotless.spotlessDaemonProvider")
    }

    private val logger = logger<Spotless>()

    internal val http = HttpClient(CIO)

    private val cleanupScope = scope + Dispatchers.IO

    private val hosts = ConcurrentHashMap<String, DaemonEntry>()

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

    /**
     * Public cleanup entry for provider implementations to release a started daemon explicitly.
     */
    public fun releaseDaemon(host: SpotlessDaemonHost) {
        val entries = hosts.entries
            .filter { it.value.host == host }
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
        if (cleanupScope.isActive) {
            cleanupScope.launch {
                stopDaemonEntry(entry, reason)
            }
            return
        }
        runBlocking(NonCancellable + Dispatchers.IO) {
            stopDaemonEntry(entry, reason)
        }
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
        val entries = hosts.values.toList()
        hosts.clear()
        runBlocking(NonCancellable + Dispatchers.IO) {
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

internal suspend fun HttpClient.stopDaemon(
    spotlessHost: SpotlessDaemonHost,
) {
    var stopFailure: Throwable? = null
    runCatching {
        post("/stop") {
            when (spotlessHost) {
                is SpotlessDaemonHost.Localhost -> host = "localhost:${spotlessHost.port}"
                is SpotlessDaemonHost.Unix -> unixSocket(spotlessHost.path.toString())
            }
            url {
                protocol = URLProtocol.HTTP
            }
        }
    }.onFailure { error ->
        stopFailure = error
    }
    var cleanupFailure: Throwable? = null
    if (spotlessHost is SpotlessDaemonHost.Unix) {
        runCatching {
            val deleted = spotlessHost.workingDirectory.toFile().deleteRecursively()
            if (!deleted && Files.exists(spotlessHost.workingDirectory)) {
                error("Failed to delete daemon temp directory: ${spotlessHost.workingDirectory}")
            }
        }.onFailure { error ->
            cleanupFailure = error
        }
    }
    cleanupFailure?.let { throw it }
    stopFailure?.let { throw it }
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
