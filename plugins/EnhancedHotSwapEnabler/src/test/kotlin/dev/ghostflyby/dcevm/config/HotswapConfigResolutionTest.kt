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

package dev.ghostflyby.dcevm.config

import org.junit.Assert.assertEquals
import org.junit.Test

internal class HotswapConfigResolutionTest {

    @Test
    fun `selects first non-inherited config`() {
        val runConfiguration = HotswapConfigState(inherit = false, enable = false, enableHotswapAgent = true)
        val projectUser = HotswapConfigState(inherit = false, enable = true, enableHotswapAgent = false)
        val app = HotswapConfigState(inherit = false, enable = true, enableHotswapAgent = true)

        val resolved = resolveHotSwapConfig(sequenceOf(runConfiguration, projectUser, app))

        assertEquals(false, resolved.enable)
        assertEquals(true, resolved.enableHotswapAgent)
    }

    @Test
    fun `skips inherited configs and falls back`() {
        val runConfiguration = HotswapConfigState(inherit = true, enable = false, enableHotswapAgent = false)
        val projectUser = HotswapConfigState(inherit = true, enable = false, enableHotswapAgent = false)
        val projectShared = HotswapConfigState(inherit = false, enable = true, enableHotswapAgent = false)
        val app = HotswapConfigState(inherit = false, enable = false, enableHotswapAgent = true)

        val resolved = resolveHotSwapConfig(sequenceOf(runConfiguration, projectUser, projectShared, app))

        assertEquals(true, resolved.enable)
        assertEquals(false, resolved.enableHotswapAgent)
    }
}
