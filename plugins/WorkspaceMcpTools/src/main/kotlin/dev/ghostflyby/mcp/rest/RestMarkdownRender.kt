/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.rest

/**
 * Prefix Block format: compresses repeated directory prefixes.
 * Each prefix block holds up to 16 entries before repeating the prefix.
 *
 * @ <prefix>/
 * <filename>
 * ...
 */
internal fun renderPrefixBlock(paths: List<String>): String = buildString {
    var currentPrefix: String? = null
    var count = 0
    for (path in paths) {
        val lastSlash = path.lastIndexOf('/')
        val prefix = if (lastSlash >= 0) path.substring(0, lastSlash + 1) else ""
        val name = if (lastSlash >= 0) path.substring(lastSlash + 1) else path

        if (prefix != currentPrefix || count >= 16) {
            if (prefix.isNotEmpty()) appendLine("@ $prefix") else appendLine("@ ")
            currentPrefix = prefix
            count = 0
        }
        appendLine(name)
        count++
    }
}

internal fun fencedCode(content: String, language: String): String {
    val fenceLength = Regex("`{3,}").findAll(content).maxOfOrNull { it.value.length }?.plus(1) ?: 3
    val fence = "`".repeat(fenceLength)
    return buildString {
        append(fence)
        appendLine(language)
        append(content)
        if (!content.endsWith('\n')) appendLine()
        appendLine(fence)
    }
}
