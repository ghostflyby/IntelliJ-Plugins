/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mill.project

import dev.ghostflyby.mill.MillConstants
import java.nio.file.Path

public data class MillDiscoveredModule(
    public val displayName: String,
    public val targetPrefix: String,
    public val projectRoot: Path,
    public val directory: Path,
    public val productionModulePrefix: String? = null,
) {
    public val isTestModule: Boolean
        get() = productionModulePrefix != null || targetPrefix.endsWith(".test")

    public fun queryTarget(suffix: String): String {
        return if (targetPrefix == MillConstants.ROOT_MODULE_PREFIX) suffix else "$targetPrefix.$suffix"
    }
}
