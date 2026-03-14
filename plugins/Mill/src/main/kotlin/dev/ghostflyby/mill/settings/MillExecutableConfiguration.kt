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

package dev.ghostflyby.mill.settings

import dev.ghostflyby.mill.MillConstants

internal enum class MillExecutableSource {
    PROJECT_DEFAULT_SCRIPT,
    PATH,
    MANUAL,
}

internal data class MillExecutableConfiguration(
    val source: MillExecutableSource,
    val manualPath: String,
)

internal object MillExecutableConfigurationUtil {
    @JvmStatic
    fun normalize(
        source: MillExecutableSource?,
        configuredPath: String?,
    ): MillExecutableConfiguration {
        val normalizedSource = source ?: MillExecutableSource.PROJECT_DEFAULT_SCRIPT
        val rawPath = configuredPath.orEmpty().trim()
        if (normalizedSource == MillExecutableSource.MANUAL) {
            return MillExecutableConfiguration(MillExecutableSource.MANUAL, rawPath)
        }
        if (
            normalizedSource == MillExecutableSource.PROJECT_DEFAULT_SCRIPT &&
            rawPath.isNotEmpty() &&
            rawPath != MillConstants.defaultExecutable
        ) {
            // Treat legacy custom executable values as an explicit manual override.
            return MillExecutableConfiguration(MillExecutableSource.MANUAL, rawPath)
        }
        return MillExecutableConfiguration(normalizedSource, "")
    }
}
