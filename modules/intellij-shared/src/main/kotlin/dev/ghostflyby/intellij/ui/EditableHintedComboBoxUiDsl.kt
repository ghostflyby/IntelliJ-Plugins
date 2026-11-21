/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.intellij.ui

import com.intellij.openapi.observable.properties.ObservableMutableProperty
import com.intellij.openapi.observable.properties.ObservableProperty
import com.intellij.openapi.observable.util.bind
import com.intellij.openapi.observable.util.lockOrSkip
import com.intellij.ui.NewUI
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.Row
import java.awt.event.ItemEvent
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.ComboBoxModel
import javax.swing.DefaultComboBoxModel
import javax.swing.ListCellRenderer

public fun <T> Cell<EditableHintedComboBox<T>>.bindRightHint(
    property: ObservableProperty<String>,
): Cell<EditableHintedComboBox<T>> = applyToComponent {
    rightHint = property.get()
    val mutex = AtomicBoolean()
    property.afterChange {
        mutex.lockOrSkip {
            rightHint = it
        }
    }
}

public fun <T> Cell<EditableHintedComboBox<T>>.bindTrailingHint(
    property: ObservableProperty<String>,
): Cell<EditableHintedComboBox<T>> = applyToComponent {
    trailingHint = property.get()
    val mutex = AtomicBoolean()
    property.afterChange {
        mutex.lockOrSkip {
            trailingHint = it
        }
    }
}


public fun <T> Cell<EditableHintedComboBox<T>>.bindText(
    property: ObservableMutableProperty<String>,
): Cell<EditableHintedComboBox<T>> = applyToComponent {
    editorTextField.bind(property)
}

/**
 * Binds the resolved selected item two-way. Committing unresolved editor text never changes the
 * selection, so the property only moves when an item is actually resolved and selected.
 */
public fun <T> Cell<EditableHintedComboBox<T>>.bindSelectedItem(
    property: ObservableMutableProperty<T?>,
): Cell<EditableHintedComboBox<T>> = applyToComponent {
    selectedItem = property.get()
    val mutex = AtomicBoolean()
    addItemListener { event ->
        if (event.stateChange == ItemEvent.SELECTED) {
            mutex.lockOrSkip {
                @Suppress("UNCHECKED_CAST")
                property.set(event.item as T?)
            }
        }
    }
    property.afterChange { value ->
        mutex.lockOrSkip {
            selectedItem = value
        }
    }
}

public fun <T> Row.editableHintedComboBox(
    model: ComboBoxModel<T>,
    adapter: EditableHintedComboBoxAdapter<T> = EditableHintedComboBox.defaultAdapter(),
    unresolvedTextPolicy: UnresolvedTextPolicy = UnresolvedTextPolicy.KEEP_DRAFT,
    renderer: ListCellRenderer<in T>? = null,
): Cell<EditableHintedComboBox<T>> {
    val box: EditableHintedComboBox<T> =
        EditableHintedComboBox(model = model, adapter = adapter, unresolvedTextPolicy = unresolvedTextPolicy)
    if (renderer == null) {
        if (!NewUI.isEnabled()) {
            box.renderer = SimpleListCellRenderer.create("") { it.toString() }
        }
    } else {
        box.renderer = renderer
    }

    return cell(box)

}

public fun <T> Row.editableHintedComboBox(
    items: Collection<T>,
    adapter: EditableHintedComboBoxAdapter<T> = EditableHintedComboBox.defaultAdapter(),
    unresolvedTextPolicy: UnresolvedTextPolicy = UnresolvedTextPolicy.KEEP_DRAFT,
    renderer: ListCellRenderer<in T>? = null,
): Cell<EditableHintedComboBox<T>> {
    return editableHintedComboBox(DefaultComboBoxModel(Vector(items)), adapter, unresolvedTextPolicy, renderer)
}
