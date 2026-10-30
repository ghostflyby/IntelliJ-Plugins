/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
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
