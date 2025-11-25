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
import com.intellij.openapi.project.Project
import dev.ghostflyby.spotless.SpotlessFormatResult.*
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.nio.file.Path


internal const val spotlessNotificationGroupId = "Spotless Notifications"

@Service(Service.Level.PROJECT)
public class Spotless(private val project: Project, private val scope: CoroutineScope) : Disposable {

    private val externalProjectToDaemons = mutableMapOf<Path, Path>()
    private val http = HttpClient(CIO)

    public suspend fun format(externalProject: Path, file: Path, content: String): SpotlessFormatResult? = scope.async {
        val daemon = externalProjectToDaemons.getOrPut(externalProject) {
            val ext = SpotlessExtension.EP_NAME.findFirstSafe { it.isApplicableTo(project, externalProject) }
                ?: return@async null
            ext.startDaemon(project, externalProject)
        }
        http.format(daemon, file, content)
    }.await()

    override fun dispose() {
        runBlocking {
            scope.launch {
                externalProjectToDaemons.values.forEach {
                    async {
                        http.post("/stop") {
                            unixSocket(it.toString())
                        }
                    }
                }
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

private suspend fun HttpClient.format(unixSocket: Path, path: Path, content: String): SpotlessFormatResult {
    val response = post("/") {
        unixSocket(unixSocket.toString())
        parameter("path", path)
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