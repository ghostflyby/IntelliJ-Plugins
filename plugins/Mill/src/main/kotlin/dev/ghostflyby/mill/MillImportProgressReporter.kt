/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
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
