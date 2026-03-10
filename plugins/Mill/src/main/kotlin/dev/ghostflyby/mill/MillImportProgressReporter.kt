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

@file:Suppress("UnstableApiUsage")

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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, see
 * <https://www.gnu.org/licenses/>.
 */

package dev.ghostflyby.mill

import com.intellij.build.events.BuildEvents
import com.intellij.build.events.impl.FailureResultImpl
import com.intellij.build.events.impl.SuccessResultImpl
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import com.intellij.openapi.externalSystem.model.task.event.ExternalSystemBuildEvent

// BuildEvents is still experimental on 2025.3, but it is the public way to
// surface structured progress in the External System import flow.
// External resolvers may run in a remote process where no IDE application is
// available, so progress reporting must degrade gracefully there.
internal class MillImportProgressReporter(
    private val taskId: ExternalSystemTaskId,
    private val listener: ExternalSystemTaskNotificationListener,
) {
    private val events: BuildEvents? = runCatching {
        val application = ApplicationManager.getApplication() ?: return@runCatching null
        application.getService(BuildEvents::class.java)
    }.getOrNull()
    private val eventId: String = "mill-import:${taskId.id}"

    fun started(message: String) {
        val events = events ?: return
        listener.onStatusChange(
            ExternalSystemBuildEvent(
                taskId,
                events.start()
                    .withId(eventId)
                    .withParentId(taskId)
                    .withTime(System.currentTimeMillis())
                    .withMessage(message)
                    .build(),
            ),
        )
    }

    fun progress(progress: Long, message: String) {
        val events = events ?: return
        listener.onStatusChange(
            ExternalSystemBuildEvent(
                taskId,
                events.progress()
                    .withStartId(eventId)
                    .withParentId(taskId)
                    .withTime(System.currentTimeMillis())
                    .withMessage(message)
                    .withTotal(100)
                    .withProgress(progress.coerceIn(0, 100))
                    .withUnit("%")
                    .build(),
            ),
        )
    }

    fun finished(message: String) {
        val events = events ?: return
        listener.onStatusChange(
            ExternalSystemBuildEvent(
                taskId,
                events.finish()
                    .withStartId(eventId)
                    .withParentId(taskId)
                    .withTime(System.currentTimeMillis())
                    .withMessage(message)
                    .withResult(SuccessResultImpl())
                    .build(),
            ),
        )
    }

    fun failed(message: String, error: Throwable) {
        val events = events ?: return
        listener.onStatusChange(
            ExternalSystemBuildEvent(
                taskId,
                events.finish()
                    .withStartId(eventId)
                    .withParentId(taskId)
                    .withTime(System.currentTimeMillis())
                    .withMessage(message)
                    .withResult(FailureResultImpl(message, error))
                    .build(),
            ),
        )
    }
}
