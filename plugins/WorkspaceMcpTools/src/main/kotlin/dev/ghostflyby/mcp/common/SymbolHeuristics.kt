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

package dev.ghostflyby.mcp.common

internal fun isLikelyTypeDeclarationClassName(className: String): Boolean {
    return className.contains("Class", ignoreCase = true) ||
            className.contains("Interface", ignoreCase = true) ||
            className.contains("Enum", ignoreCase = true) ||
            className.contains("Record", ignoreCase = true) ||
            className.contains("Object", ignoreCase = true) ||
            className.contains("TypeAlias", ignoreCase = true) ||
            className.contains("Struct", ignoreCase = true) ||
            className.contains("Trait", ignoreCase = true) ||
            className.contains("TypeDef", ignoreCase = true)
}

internal fun isLikelyCallableDeclarationClassName(className: String): Boolean {
    return className.contains("Method", ignoreCase = true) ||
            className.contains("Function", ignoreCase = true) ||
            className.contains("Callable", ignoreCase = true) ||
            className.contains("Constructor", ignoreCase = true) ||
            className.contains("Ctor", ignoreCase = true)
}

internal fun isLikelyFieldDeclarationClassName(className: String): Boolean {
    return className.contains("Field", ignoreCase = true) ||
            className.contains("Property", ignoreCase = true) ||
            className.contains("Variable", ignoreCase = true)
}
