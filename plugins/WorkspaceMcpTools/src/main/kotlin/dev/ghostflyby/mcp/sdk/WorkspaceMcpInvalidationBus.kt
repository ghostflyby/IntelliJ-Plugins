/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.sdk

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

internal class WorkspaceMcpInvalidationBus(
    scope: CoroutineScope,
    private val dispatcher: WorkspaceMcpNotificationDispatcher,
    private val coalesceWindow: Duration = 100.milliseconds,
) : WorkspaceMcpInvalidationSink {
    private val channel = Channel<WorkspaceMcpInvalidation>(
        capacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    init {
        scope.launch {
            channel.batchedInvalidations(coalesceWindow).collect { invalidations ->
                dispatcher.dispatch(invalidations)
            }
        }
    }

    override fun invalidateResource(uri: String) {
        if (!WorkspaceMcpResourceSubscriptionService.isWorkspaceResourceUri(uri)) return
        channel.trySend(WorkspaceMcpInvalidation.Resource(uri))
    }

    override fun invalidateResourceList(selector: ResourceListSelector) {
        channel.trySend(WorkspaceMcpInvalidation.ResourceList(selector))
    }

    override fun invalidateToolList() {
        channel.trySend(WorkspaceMcpInvalidation.ToolList)
    }
}

internal fun ReceiveChannel<WorkspaceMcpInvalidation>.batchedInvalidations(
    coalesceWindow: Duration,
): kotlinx.coroutines.flow.Flow<CoalescedInvalidations> = kotlinx.coroutines.flow.flow {
    for (first in this@batchedInvalidations) {
        var batch = CoalescedInvalidations().plus(first)
        withTimeoutOrNull(coalesceWindow) {
            while (true) {
                val next = receiveCatching().getOrNull() ?: break
                batch = batch.plus(next)
            }
        }
        emit(batch)
    }
}
