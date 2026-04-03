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

package dev.ghostflyby.intellij.ui

import com.intellij.openapi.observable.properties.ObservableMutableProperty
import com.intellij.openapi.observable.properties.ObservableProperty
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.MutableProperty
import com.intellij.ui.dsl.builder.toMutableProperty
import kotlin.reflect.KMutableProperty0

public fun <T> Cell<EditableHintedComboBox<T>>.bindItems(
    property: ObservableProperty<List<T>>,
): Cell<EditableHintedComboBox<T>> = applyToComponent {
    bindItemsInternal(property)
}

public fun <T> Cell<EditableHintedComboBox<T>>.bindSelectedValue(
    property: ObservableMutableProperty<T?>,
): Cell<EditableHintedComboBox<T>> = applyToComponent {
    bindSelectedValueInternal(property)
}

public fun <T> Cell<EditableHintedComboBox<T>>.bindSelectedValue(
    prop: MutableProperty<T?>,
): Cell<EditableHintedComboBox<T>> {
    return bind(
        componentGet = { it.selectedValue },
        componentSet = { component, value -> component.selectedValue = value },
        prop = prop,
    )
}

public fun <T> Cell<EditableHintedComboBox<T>>.bindSelectedValue(
    prop: KMutableProperty0<T?>,
): Cell<EditableHintedComboBox<T>> {
    return bindSelectedValue(prop.toMutableProperty())
}

public fun <T> Cell<EditableHintedComboBox<T>>.bindSelectedValue(
    getter: () -> T?,
    setter: (T?) -> Unit,
): Cell<EditableHintedComboBox<T>> {
    return bindSelectedValue(MutableProperty(getter, setter))
}

public fun <T> Cell<EditableHintedComboBox<T>>.bindEditorText(
    property: ObservableMutableProperty<String>,
): Cell<EditableHintedComboBox<T>> = applyToComponent {
    bindEditorTextInternal(property)
}

public fun <T> Cell<EditableHintedComboBox<T>>.bindEditorText(
    prop: MutableProperty<String>,
): Cell<EditableHintedComboBox<T>> {
    return bind(
        componentGet = { it.editorText },
        componentSet = { component, value -> component.editorText = value },
        prop = prop,
    )
}

public fun <T> Cell<EditableHintedComboBox<T>>.bindEditorText(
    prop: KMutableProperty0<String>,
): Cell<EditableHintedComboBox<T>> {
    return bindEditorText(prop.toMutableProperty())
}

public fun <T> Cell<EditableHintedComboBox<T>>.bindEditorText(
    getter: () -> String,
    setter: (String) -> Unit,
): Cell<EditableHintedComboBox<T>> {
    return bindEditorText(MutableProperty(getter, setter))
}

public fun <T> Cell<EditableHintedComboBox<T>>.bindLeftHint(
    property: ObservableProperty<String>,
): Cell<EditableHintedComboBox<T>> = applyToComponent {
    bindLeftHintInternal(property)
}

public fun <T> Cell<EditableHintedComboBox<T>>.bindRightHint(
    property: ObservableProperty<String>,
): Cell<EditableHintedComboBox<T>> = applyToComponent {
    bindRightHintInternal(property)
}

public fun <T> Cell<EditableHintedComboBox<T>>.bindRightHintError(
    property: ObservableProperty<Boolean>,
): Cell<EditableHintedComboBox<T>> = applyToComponent {
    bindRightHintErrorInternal(property)
}
