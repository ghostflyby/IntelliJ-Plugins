/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.dcevm.config

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

internal class HotswapConfigResolutionTest {

    @Test
    fun `selects first non-inherited config`() {
        val runConfiguration = HotswapConfigState(inherit = false, enable = false, enableHotswapAgent = true)
        val projectUser = HotswapConfigState(inherit = false, enable = true, enableHotswapAgent = false)
        val app = HotswapConfigState(inherit = false, enable = true, enableHotswapAgent = true)

        val resolved = resolveHotSwapConfig(sequenceOf(runConfiguration, projectUser, app))

        Assertions.assertEquals(false, resolved.enable)
        Assertions.assertEquals(true, resolved.enableHotswapAgent)
    }

    @Test
    fun `skips inherited configs and falls back`() {
        val runConfiguration = HotswapConfigState(inherit = true, enable = false, enableHotswapAgent = false)
        val projectUser = HotswapConfigState(inherit = true, enable = false, enableHotswapAgent = false)
        val projectShared = HotswapConfigState(inherit = false, enable = true, enableHotswapAgent = false)
        val app = HotswapConfigState(inherit = false, enable = false, enableHotswapAgent = true)

        val resolved = resolveHotSwapConfig(sequenceOf(runConfiguration, projectUser, projectShared, app))

        Assertions.assertEquals(true, resolved.enable)
        Assertions.assertEquals(false, resolved.enableHotswapAgent)
    }
}
