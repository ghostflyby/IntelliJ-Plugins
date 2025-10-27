/*
 * Copyright (c) 2025 ghostflyby
 * SPDX-FileCopyrightText: 2025 ghostflyby
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

import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.common.EditorListener
import com.maddyhome.idea.vim.common.ModeChangeListener
import com.maddyhome.idea.vim.extension.VimExtension
import com.maddyhome.idea.vim.newapi.ij
import com.maddyhome.idea.vim.state.mode.Mode


internal class DisableImeInNormal : VimExtension {
    override fun getName() = "autotoggleime"

    override fun init() {
        @Suppress("UnstableApiUsage")
        injector.listenersNotifier.modeChangeListeners.add(ImeVimModeListener)
        @Suppress("UnstableApiUsage")
        injector.listenersNotifier.myEditorListeners.add(ImeEditorListener)
    }
}

private object ImeVimModeListener : ModeChangeListener {
    override fun modeChanged(editor: VimEditor, oldMode: Mode) {
        editor.toggleIme()
    }
}

private fun VimEditor.toggleIme() {
    val ime = when (mode) {
        is Mode.NORMAL, is Mode.VISUAL, is Mode.SELECT -> false
        else -> true
    }
    ij.contentComponent.enableInputMethods(ime)
}

private object ImeEditorListener : EditorListener {
    override fun focusGained(editor: VimEditor) {
        editor.toggleIme()
    }

    override fun created(editor: VimEditor) {
        editor.toggleIme()
    }
}