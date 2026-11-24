/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mill.settings

import dev.ghostflyby.mill.MillConstants

public enum class MillExecutableSource {
    PROJECT_DEFAULT_SCRIPT,
    MANUAL,
}

public data class MillExecutableConfiguration(
    public val source: MillExecutableSource,
    public val manualPath: String,
)

public object MillExecutableConfigurationUtil {
    @JvmStatic
    public fun normalize(
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
            rawPath != MillConstants.DEFAULT_EXECUTABLE
        ) {
            // Treat legacy custom executable values as an explicit manual override.
            return MillExecutableConfiguration(MillExecutableSource.MANUAL, rawPath)
        }
        return MillExecutableConfiguration(normalizedSource, "")
    }
}
