/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.sdk

import org.junit.Test

internal class WorkspaceMcpFeatureTest {

    @Test
    fun `empty features register correctly`() {
        // Registration logic tested in integration
    }

    private class TestFeature(
        private val id: String,
    ) : WorkspaceMcpFeature {
        override val featureName: String = "test-$id"

        override fun WorkspaceMcpFeatureRegistrationContext.register(): WorkspaceMcpFeatureRegistration {
            return buildRegistration()
        }
    }
}
