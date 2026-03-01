/*
 * Copyright (c) 2025-2026 ghostflyby
 * SPDX-FileCopyrightText: 2025-2026 ghostflyby
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

package dev.ghostflyby.selectionlivetemplate

import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.*
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.KeyWithDefaultValue
import dev.ghostflyby.intellij.getValue
import dev.ghostflyby.intellij.setValue
import dev.ghostflyby.intellij.toAutoCleanKey

internal class SelectionTemplatesEditorFactoryListener : EditorFactoryListener {

    override fun editorCreated(event: EditorFactoryEvent) =
        event.editor.run {
            if (isViewer || document.listenerAttached) return
            document.listenerAttached = true
            if (selectionModel.hasSelection()) {
                document.previousSelection = selectionModel.selectedText
            }
            document.addDocumentListener(MyDocumentListener, PluginDisposable)
            selectionModel.addSelectionListener(MySelectionListener, PluginDisposable)
        }

}

private val previousSelectionKey = Key<String>("previousSelection").toAutoCleanKey(PluginDisposable)
private val replacedSelectionKey = Key<String>("replacedSelection").toAutoCleanKey(PluginDisposable)

private val listenerAttachedKey =
    KeyWithDefaultValue<Boolean>.create("selectionTemplateListenerAttached", false).toAutoCleanKey(PluginDisposable)

private var Document.previousSelection by previousSelectionKey
internal var Document.replacedSelection by replacedSelectionKey
private var Document.listenerAttached: Boolean by listenerAttachedKey

private object MySelectionListener : SelectionListener {
    override fun selectionChanged(e: SelectionEvent) = e.editor.run {
        if (!selectionModel.hasSelection()) {
            document.replacedSelection = null
            return
        }
        document.previousSelection = selectionModel.selectedText
    }
}

private object MyDocumentListener : DocumentListener {
    override fun documentChanged(event: DocumentEvent) {
        val doc = event.document

        val previousSelection = doc.previousSelection ?: return

        if (previousSelection.contentEquals(event.oldFragment)) {
            doc.replacedSelection = previousSelection
        }
    }
}
