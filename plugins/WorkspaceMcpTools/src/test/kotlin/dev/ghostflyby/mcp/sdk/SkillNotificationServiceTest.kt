/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.sdk

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Path

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
    fun `bundled skill path is resolved inside plugin directory`() {
        val pluginPath = Path.of("/plugins/WorkspaceMcpTools")

        assertEquals(
            Path.of("/plugins/WorkspaceMcpTools/agent-skills/workspace-mcp-rest-api"),
            bundledSkillPath(pluginPath),
        )
    }

    @Test
    fun `missing plugin path has no bundled skill path`() {
        assertEquals(null, bundledSkillPath(null))
    }

    @Test
    fun `plugin path is resolved from main jar in lib directory`() {
        assertEquals(
            Path.of("/plugins/WorkspaceMcpTools"),
            pluginPathFromMainJarLocation(Path.of("/plugins/WorkspaceMcpTools/lib/WorkspaceMcpTools.jar")),
        )
    }

    @Test
    fun `plugin path falls back to main jar parent directory`() {
        assertEquals(
            Path.of("/plugins/WorkspaceMcpTools"),
            pluginPathFromMainJarLocation(Path.of("/plugins/WorkspaceMcpTools/WorkspaceMcpTools.jar")),
        )
    }

    @Test
    fun `missing main jar path has no plugin path`() {
        assertEquals(null, pluginPathFromMainJarLocation(null))
    }
}
