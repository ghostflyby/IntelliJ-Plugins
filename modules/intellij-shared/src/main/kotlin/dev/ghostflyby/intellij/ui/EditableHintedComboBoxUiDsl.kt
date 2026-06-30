/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.intellij.ui

import com.intellij.openapi.observable.properties.ObservableProperty
import com.intellij.openapi.observable.util.lockOrSkip
import com.intellij.ui.NewUI
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.Row
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

public fun <T> Row.editableHintedComboBox(
    model: ComboBoxModel<T>,
    renderer: ListCellRenderer<in T>? = null,
): Cell<EditableHintedComboBox<T>> {
    val box: EditableHintedComboBox<T> = EditableHintedComboBox(model = model)
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
    renderer: ListCellRenderer<in T>? = null,
): Cell<EditableHintedComboBox<T>> {
    return editableHintedComboBox(DefaultComboBoxModel(Vector(items)), renderer)
}