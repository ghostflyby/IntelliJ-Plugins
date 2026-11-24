/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mill

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class FrontendDescriptorTest {

    @Test
    fun `frontend module carries the UI surface`() {
        val descriptor = descriptor()
        assertTrue(
            "<module name=\"intellij.platform.frontend\"/>" in descriptor,
            "the frontend module must declare the frontend platform module",
        )
        assertTrue(
            "<module name=\"dev.ghostflyby.mill.shared\"/>" in descriptor,
            "the frontend module must depend on the shared content module",
        )
        assertTrue(
            "<module name=\"dev.ghostflyby.intellij.shared\"/>" in descriptor,
            "the settings widgets ship as their own content module",
        )
        assertTrue("projectConfigurable" in descriptor)
        assertTrue("toolWindow" in descriptor)
        assertTrue("externalSystemViewContributor" in descriptor)
        assertTrue("notificationGroup" in descriptor)
    }

    @Test
    fun `frontend module does not carry backend-only integration points`() {
        val descriptor = descriptor()
        assertFalse("intellij.platform.backend" in descriptor)
        assertFalse(
            "externalSystemManager" in descriptor,
            "the external-system resolver/manager side stays in the backend module",
        )
        assertFalse(
            "externalProjectDataService" in descriptor,
            "data services populate DataNodes on the backend side",
        )
    }

    private fun descriptor(): String {
        return javaClass.classLoader
            .getResource("dev.ghostflyby.mill.frontend.xml")?.readText()
            .also { assertNotNull(it, "frontend module descriptor must be on the test classpath") }
            .orEmpty()
    }
}
