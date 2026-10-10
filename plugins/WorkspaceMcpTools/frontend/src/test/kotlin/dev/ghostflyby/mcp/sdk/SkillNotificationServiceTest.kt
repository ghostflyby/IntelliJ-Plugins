/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.sdk

import com.intellij.testFramework.junit5.TestApplication
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@TestApplication
internal class SkillNotificationServiceTest {

    @Test
    fun `unknown version does not notify`() {
        assertFalse(shouldNotifySkill(currentVersion = "unknown", notifiedVersion = ""))
        assertFalse(shouldNotifySkill(currentVersion = "unknown", notifiedVersion = "1.0.4"))
    }

    @Test
    fun `same version does not notify again`() {
        assertFalse(shouldNotifySkill(currentVersion = "1.0.4", notifiedVersion = "1.0.4"))
    }

    @Test
    fun `empty notified version notifies current version`() {
        assertTrue(shouldNotifySkill(currentVersion = "1.0.4", notifiedVersion = ""))
    }

    @Test
    fun `different notified version notifies current version`() {
        assertTrue(shouldNotifySkill(currentVersion = "1.0.5", notifiedVersion = "1.0.4"))
    }

    @Test
    fun `notification activity does not run in unit test mode`() {
        assertFalse(shouldRunSkillNotificationActivity())
    }

}
