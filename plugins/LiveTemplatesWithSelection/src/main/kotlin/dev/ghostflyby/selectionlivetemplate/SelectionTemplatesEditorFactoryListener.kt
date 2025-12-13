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

package dev.ghostflyby.selectionlivetemplate

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.*
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key

@Service
internal class PluginDisposable : Disposable.Default

internal class SelectionTemplatesEditorFactoryListener : EditorFactoryListener {

    override fun editorCreated(event: EditorFactoryEvent) =
        event.editor.run {
            if (isViewer || document.getUserData(listenerAttachedKey) != null) return
            document.putUserData(listenerAttachedKey, Unit)
            val disposable = service<PluginDisposable>()
            if (selectionModel.hasSelection()) {
                document.previousSelection = selectionModel.selectedText
            }
            document.addDocumentListener(MyDocumentListener, disposable)
            Disposer.register(disposable) {
                document.previousSelection = null
                document.replacedSelection = null
            }
            selectionModel.addSelectionListener(MySelectionListener, disposable)
        }

}

private val previousSelectionKey = Key<String>("previousSelection")
private val replacedSelectionKey = Key<String>("replacedSelection")

private val listenerAttachedKey = Key<Unit>("selectionTemplateListenerAttached")

private var Document.previousSelection by previousSelectionKey
internal var Document.replacedSelection by replacedSelectionKey

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
