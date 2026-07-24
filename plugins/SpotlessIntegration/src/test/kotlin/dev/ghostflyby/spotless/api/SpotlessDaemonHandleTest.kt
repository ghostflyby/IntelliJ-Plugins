/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.spotless.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

internal class SpotlessDaemonHandleTest {
    @Test
    fun `handle exposes immutable endpoint and exact lifetime job`() {
        val endpoint = SpotlessDaemonProvider.Endpoint.Localhost(25252U)
        val lifetime = Job()
        val handle = SpotlessDaemonProvider.Handle(endpoint, lifetime)

        assertEquals(endpoint, handle.endpoint)
        assertSame(lifetime, handle.lifetime)
        assertTrue(Modifier.isFinal(SpotlessDaemonProvider.Handle::class.java.modifiers))
    }

    @Test
    fun `lifetime cancellation and completion handlers use backing job`() = runBlocking {
        val lifetime = Job()
        val handle = SpotlessDaemonProvider.Handle(
            endpoint = SpotlessDaemonProvider.Endpoint.Localhost(25252U),
            lifetime = lifetime,
        )
        var completionCause: Throwable? = null
        handle.lifetime.invokeOnCompletion { completionCause = it }

        handle.lifetime.cancelAndJoin()

        assertTrue(lifetime.isCancelled)
        assertTrue(completionCause is CancellationException)
    }
}
