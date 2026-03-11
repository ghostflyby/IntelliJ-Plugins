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

package dev.ghostflyby.mill

import com.intellij.openapi.diagnostic.logger

internal object MillImportDebugLogger {
    private const val prefix = "[Mill import] "
    private val logger = logger<MillImportDebugLogger>()

    fun info(message: String) {
        logger.info(prefix + message)
    }

    fun warn(message: String, error: Throwable? = null) {
        if (error == null) {
            logger.warn(prefix + message)
        } else {
            logger.warn(prefix + message, error)
        }
    }

    fun sample(values: Collection<*>, limit: Int = 8): String {
        if (values.isEmpty()) return "[]"
        val rendered = values.take(limit).joinToString(prefix = "[", postfix = if (values.size > limit) ", ...]" else "]")
        return rendered
    }

    fun trim(text: String, maxLength: Int = 400): String {
        val normalized = text.trim().replace('\n', ' ').replace('\r', ' ')
        return if (normalized.length <= maxLength) normalized else normalized.take(maxLength) + "..."
    }
}
