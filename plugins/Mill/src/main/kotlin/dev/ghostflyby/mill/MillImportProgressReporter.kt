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


package dev.ghostflyby.mill

import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import com.intellij.openapi.externalSystem.model.task.event.*

// These external-system progress DTOs are stable contracts. The IDE already
// converts them into build view events, so they avoid direct use of the
// experimental BuildEvents service in the remote resolver process.
internal class MillImportProgressReporter(
    private val taskId: ExternalSystemTaskId,
    private val listener: ExternalSystemTaskNotificationListener,
) {
    private val eventId: String = "mill-import:${taskId.id}"
    private val startedAt: Long = System.currentTimeMillis()

    fun started(message: String) {
        listener.onStatusChange(
            ExternalSystemTaskExecutionEvent(
                taskId,
                ExternalSystemStartEvent(
                    eventId,
                    null,
                    OperationDescriptor(message, System.currentTimeMillis()),
                ),
            ),
        )
    }

    fun progress(progress: Long, message: String) {
        listener.onStatusChange(
            ExternalSystemTaskExecutionEvent(
                taskId,
                ExternalSystemStatusEvent(
                    eventId,
                    null,
                    OperationDescriptor(message, System.currentTimeMillis()),
                    100,
                    progress.coerceIn(0, 100),
                    "%",
                    message,
                ),
            ),
        )
    }

    fun finished(message: String) {
        val finishedAt = System.currentTimeMillis()
        listener.onStatusChange(
            ExternalSystemTaskExecutionEvent(
                taskId,
                ExternalSystemFinishEvent(
                    eventId,
                    null,
                    OperationDescriptor(message, finishedAt),
                    SuccessResult(startedAt, finishedAt, false),
                ),
            ),
        )
    }

    fun failed(message: String, error: Throwable) {
        val finishedAt = System.currentTimeMillis()
        listener.onStatusChange(
            ExternalSystemTaskExecutionEvent(
                taskId,
                ExternalSystemFinishEvent(
                    eventId,
                    null,
                    OperationDescriptor(message, finishedAt),
                    FailureResult(
                        startedAt,
                        finishedAt,
                        listOf(Failure(message, error.stackTraceToString(), emptyList())),
                    ),
                ),
            ),
        )
    }
}
