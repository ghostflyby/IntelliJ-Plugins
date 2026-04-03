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

package dev.ghostflyby.mill.sdk

import java.nio.file.Path

internal const val MillModuleJdkHomeProperty: String = "mill.jdk.home"

internal object MillModuleJdkSupport {
    fun normalizeJdkHomePath(rawValue: String?): String? {
        val pathValue = rawValue?.trim()?.takeIf(String::isNotBlank) ?: return null
        return runCatching { Path.of(pathValue).toAbsolutePath().normalize().toString() }.getOrNull()
    }

    fun createUniqueSdkName(baseName: String, existingNames: Set<String>): String {
        if (baseName !in existingNames) {
            return baseName
        }

        var suffix = 2
        while (true) {
            val candidate = "$baseName ($suffix)"
            if (candidate !in existingNames) {
                return candidate
            }
            suffix += 1
        }
    }
}
