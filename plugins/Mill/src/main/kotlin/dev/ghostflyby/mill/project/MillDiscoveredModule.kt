/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mill.project

import dev.ghostflyby.mill.MillConstants
import java.nio.file.Path

internal data class MillDiscoveredModule(
    val displayName: String,
    val targetPrefix: String,
    val projectRoot: Path,
    val directory: Path,
    val productionModulePrefix: String? = null,
) {
    val isTestModule: Boolean
        get() = productionModulePrefix != null || targetPrefix.endsWith(".test")

    fun queryTarget(suffix: String): String {
        return if (targetPrefix == MillConstants.rootModulePrefix) suffix else "$targetPrefix.$suffix"
    }
}
