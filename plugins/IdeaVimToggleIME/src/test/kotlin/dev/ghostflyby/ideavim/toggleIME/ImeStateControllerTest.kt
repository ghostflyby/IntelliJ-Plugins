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

package dev.ghostflyby.ideavim.toggleIME

import com.maddyhome.idea.vim.state.mode.Mode
import com.maddyhome.idea.vim.state.mode.SelectionType
import org.junit.Assert.assertEquals
import org.junit.Test

class ImeStateControllerTest {

    private class FakeTarget(override var mode: Mode) : ImeTarget {
        var lastEnabled: Boolean? = null

        override fun setImeEnabled(enabled: Boolean) {
            lastEnabled = enabled
        }
    }

    @Test
    fun normalVisualSelectDisableIme() {
        val target = FakeTarget(Mode.NORMAL())

        ImeStateController.update(target)
        assertEquals(false, target.lastEnabled)

        target.mode = Mode.VISUAL(SelectionType.CHARACTER_WISE)
        ImeStateController.update(target)
        assertEquals(false, target.lastEnabled)

        target.mode = Mode.SELECT(SelectionType.CHARACTER_WISE)
        ImeStateController.update(target)
        assertEquals(false, target.lastEnabled)
    }

    @Test
    fun insertEnablesIme() {
        val target = FakeTarget(Mode.INSERT)

        ImeStateController.update(target)
        assertEquals(true, target.lastEnabled)
    }

    @Test
    fun focusLifecycleUsesCurrentMode() {
        val target = FakeTarget(Mode.INSERT)

        ImeStateController.update(target)
        assertEquals(true, target.lastEnabled)

        target.mode = Mode.NORMAL()
        ImeStateController.update(target)
        assertEquals(false, target.lastEnabled)
    }
}
