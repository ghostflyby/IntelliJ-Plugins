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

internal fun <T> EditableHintedComboBox<T>.bindItemsInternal(
    property: ObservableProperty<List<T>>,
): EditableHintedComboBox<T> {
    items = property.get()
    property.afterChange {
        items = it
    }
    return this
}

internal fun <T> EditableHintedComboBox<T>.bindEditorTextInternal(
    property: ObservableMutableProperty<String>,
): EditableHintedComboBox<T> {
    editorText = property.get()
    property.afterChange {
        editorText = it
    }
    whenEditorTextChanged { property.set(it) }
    return this
}

internal fun <T> EditableHintedComboBox<T>.bindLeftHintInternal(
    property: ObservableProperty<String>,
): EditableHintedComboBox<T> {
    leftHint = property.get()
    property.afterChange {
        leftHint = it
    }
    return this
}

internal fun <T> EditableHintedComboBox<T>.bindRightHintInternal(
    property: ObservableProperty<String>,
): EditableHintedComboBox<T> {
    rightHint = property.get()
    property.afterChange {
        rightHint = it
    }
    return this
}

internal fun <T> EditableHintedComboBox<T>.bindRightHintErrorInternal(
    property: ObservableProperty<Boolean>,
): EditableHintedComboBox<T> {
    isRightHintError = property.get()
    property.afterChange {
        isRightHintError = it
    }
    return this
}
