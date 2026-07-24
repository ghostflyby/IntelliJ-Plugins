/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
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
