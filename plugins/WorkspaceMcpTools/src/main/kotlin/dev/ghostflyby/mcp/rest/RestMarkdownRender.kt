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

// -- Markdown text helpers --

/**
 * Escape pipe and newline for a Markdown table cell.
 */
internal fun markdownCell(value: String): String =
    value.replace("|", "\\|").replace("\n", " ")

/**
 * Encode a YAML scalar value with double-quote escaping.
 */
internal fun yamlScalar(value: String): String {
    val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"")
    return "\"$escaped\""
}

/**
 * Append a ## Diagnostics section if [diagnostics] is non-empty.
 */
internal fun StringBuilder.appendDiagnostics(diagnostics: List<String>) {
    if (diagnostics.isNotEmpty()) {
        appendLine("## Diagnostics")
        diagnostics.forEach { appendLine("- $it") }
    }
}

/**
 * Append a ## References table from [FileRefactoringReference] entries.
 */
internal fun StringBuilder.appendRefactoringReferences(
    references: List<FileRefactoringReference>,
) {
    if (references.isEmpty()) return
    appendLine("## References")
    appendLine("| path | encodedFileUrl | line | column | usage |")
    appendLine("| --- | --- | ---: | ---: | --- |")
    references.forEach { ref ->
        val fileReference = markdownFileReference(
            ref.filePath, ref.fileUrl, ref.encodedFileUrl,
        )
        appendLine(
            "| ${markdownCell(fileReference.path)} | ${markdownCell(fileReference.encodedFileUrl)} | " +
                    "${ref.line} | ${ref.column} | ${markdownCell(ref.usageText)} |",
        )
    }
}

/**
 * Append the common YAML metadata block shared by search response frontmatter.
 */
internal fun StringBuilder.appendSearchMetadataYaml(
    limit: Int,
    timeoutMillis: Int,
    count: Int,
    truncated: Boolean,
    timedOut: Boolean,
) {
    appendLine("limit: $limit")
    appendLine("timeoutMillis: $timeoutMillis")
    appendLine("count: $count")
    appendLine("truncated: $truncated")
    appendLine("timedOut: $timedOut")
}
