/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.sdk

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class FrontendDescriptorTest {

    @Test
    fun `frontend module carries the notification surface`() {
        val descriptor = javaClass.classLoader
            .getResource("dev.ghostflyby.mcp.workspace.frontend.xml")?.readText()
            .also { assertNotNull(it, "frontend module descriptor must be on the test classpath") }
            .orEmpty()
        assertTrue("<module name=\"intellij.platform.frontend\"/>" in descriptor)
        assertTrue("notificationGroup" in descriptor)
        assertTrue("dev.ghostflyby.mcp.sdk.SkillNotificationActivity" in descriptor)
        assertFalse(
            "postStartupActivity implementation=\"dev.ghostflyby.mcp.WorkspaceMcpStartupActivity\"" in descriptor,
            "the REST server startup activity belongs to the backend module",
        )
    }
}
