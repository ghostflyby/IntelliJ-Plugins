/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.rest

import dev.ghostflyby.mcp.filecontent.DirectoryListing
import dev.ghostflyby.mcp.filecontent.FileMeta
import dev.ghostflyby.mcp.filecontent.FileStructure
import dev.ghostflyby.mcp.filecontent.StructureElement

internal fun yamlFrontMatter(values: Map<String, Any?>): String {
    return buildString {
        appendLine("---")
        values.forEach { (key, value) ->
            appendYamlValue(key, value)
        }
        appendLine("---")
    }
}

private fun StringBuilder.appendYamlValue(key: String, value: Any?) {
    when (value) {
        null -> appendLine("$key: null")
        is Boolean, is Number -> appendLine("$key: $value")
        is Iterable<*> -> {
            appendLine("$key:")
            value.forEach { item -> appendLine("  - ${yamlScalar(item)}") }
        }

        else -> appendLine("$key: ${yamlScalar(value)}")
    }
}

private fun yamlScalar(value: Any?): String {
    val text = value?.toString() ?: return "null"
    if (text.isEmpty()) return "\"\""
    val simple = text.all { it.isLetterOrDigit() || it in setOf('-', '_', '.', '/') }
    return if (simple) text else "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

internal fun renderMetaMarkdown(meta: FileMeta): String = yamlFrontMatter(metaYamlValues(meta))

internal fun renderCompoundMarkdown(
    meta: FileMeta?,
    content: String?,
    language: String,
    structure: FileStructure?,
    exists: Boolean?,
): String = buildString {
    if (meta != null || exists != null) {
        val values = linkedMapOf<String, Any?>()
        meta?.let { values.putAll(metaYamlValues(it)) }
        exists?.let { values["exists"] = it }
        append(yamlFrontMatter(values))
    }
    if (content != null) {
        if (isNotEmpty()) appendLine()
        append(fencedCode(content, language))
    }
    if (structure != null) {
        if (isNotEmpty()) appendLine()
        appendLine("## Structure")
        append(renderStructureText(structure))
    }
}

internal fun renderStructureMarkdown(structure: FileStructure): String {
    return buildString {
        appendLine("## Structure")
        append(renderStructureText(structure))
    }
}

internal fun renderDirectoryListingText(listing: DirectoryListing): String {
    return listing.children.joinToString(separator = "\n", postfix = if (listing.children.isEmpty()) "" else "\n")
}

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
    val fenceLength = Regex("`{3,}").findAll(content).map { it.value.length }.maxOrNull()?.plus(1) ?: 3
    val fence = "`".repeat(fenceLength)
    return buildString {
        append(fence)
        appendLine(language)
        append(content)
        if (!content.endsWith('\n')) appendLine()
        appendLine(fence)
    }
}

private fun renderStructureText(structure: FileStructure): String {
    return buildString {
        structure.elements.forEach { appendStructureElement(it, 0) }
    }
}

private fun StringBuilder.appendStructureElement(element: StructureElement, depth: Int) {
    repeat(depth) { append('\t') }
    append(element.name)
    if (element.type.isNotBlank()) append(" (").append(element.type).append(")")
    appendLine()
    element.children.forEach { appendStructureElement(it, depth + 1) }
}

private fun metaYamlValues(meta: FileMeta): Map<String, Any?> = linkedMapOf(
    "name" to meta.name,
    "url" to meta.url,
    "path" to meta.path,
    "isDirectory" to meta.isDirectory,
    "length" to meta.length,
    "lastModified" to meta.lastModified,
    "isWritable" to meta.isWritable,
    "fileType" to meta.fileType,
    "isBinary" to meta.isBinary,
    "charset" to meta.charset,
    "textLength" to meta.textLength,
    "lineCount" to meta.lineCount,
    "modificationStamp" to meta.modificationStamp,
    "dirty" to meta.dirty,
    "classification" to meta.classification,
    "readableKinds" to meta.readableKinds,
    "writableKinds" to meta.writableKinds,
    "requiresForceForWrite" to meta.requiresForceForWrite,
    "reason" to meta.reason,
)
