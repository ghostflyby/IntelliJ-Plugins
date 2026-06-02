/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.filecontent.tools

import com.intellij.openapi.diff.impl.patch.PatchHunk
import com.intellij.openapi.diff.impl.patch.PatchLine
import dev.ghostflyby.mcp.common.WorkspaceResourceException

internal enum class CodexFileOperation {
    ADD,
    UPDATE,
    DELETE,
}

internal data class CodexFileSection(
    val operation: CodexFileOperation,
    val filePath: String,
    val rawLines: List<String>,
)

private val CODEX_FILE = Regex("""^\*\*\* (Update|Add|Delete) File: (.+)$""")
private val HUNK_NUMBERED = Regex("""^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))?(?: @@.*)?$""")

internal fun splitCodexByFile(patch: String): List<CodexFileSection> {
    val lines = patch.lines()
    val result = mutableListOf<CodexFileSection>()
    var currentFile: String? = null
    var currentOperation: CodexFileOperation? = null
    var sectionStart = 0
    for (i in lines.indices) {
        CODEX_FILE.find(lines[i])?.let { m ->
            if (currentFile != null && sectionStart < i) {
                result += CodexFileSection(currentOperation!!, currentFile, lines.subList(sectionStart, i))
            }
            currentOperation = when (m.groupValues[1]) {
                "Add" -> CodexFileOperation.ADD
                "Delete" -> CodexFileOperation.DELETE
                else -> CodexFileOperation.UPDATE
            }
            currentFile = m.groupValues[2]
            sectionStart = i + 1
        }
    }
    if (currentFile != null && sectionStart < lines.size) {
        result += CodexFileSection(currentOperation!!, currentFile, lines.subList(sectionStart, lines.size))
    }
    return result
}

internal fun parseCodexAddContent(rawLines: List<String>): String {
    val contentLines = mutableListOf<String>()
    for (line in rawLines) {
        if (line.startsWith("***")) break
        if (!line.startsWith("+")) {
            throw WorkspaceResourceException("Add file content lines must start with '+'.")
        }
        contentLines += line.removePrefix("+")
    }
    return contentLines.joinToString("\n")
}

internal fun parseCodexRawHunks(rawLines: List<String>, baseText: CharSequence): List<PatchHunk>? {
    val baseLines = baseText.lines()
    val hunks = mutableListOf<PatchHunk>()
    var i = 0
    while (i < rawLines.size) {
        val line = rawLines[i]
        val numberedMatch = HUNK_NUMBERED.find(line)
        val (oldStart, oldEnd) = if (numberedMatch != null) {
            val s = numberedMatch.groupValues[1].toInt()
            val c = numberedMatch.groupValues[2].takeIf { it.isNotEmpty() }?.toInt() ?: 1
            (s - 1) to (s + c - 1)
        } else if (line.startsWith("@@")) {
            findContextStart(baseLines, rawLines, i + 1) ?: run {
                while (true) {
                    if (++i >= rawLines.size) break
                    val l = rawLines[i]
                    if (l.startsWith("@@") || l.startsWith("***")) break
                }
                continue
            }
        } else {
            i++; continue
        }
        val newStart = numberedMatch?.let { it.groupValues[3].toInt() - 1 } ?: oldStart
        var newLineCount = 0
        val newLines = mutableListOf<PatchLine>()
        i++
        while (i < rawLines.size && !rawLines[i].startsWith("@@") && !rawLines[i].startsWith("***")) {
            val hl = rawLines[i]
            when {
                hl.startsWith("+") -> { newLines += PatchLine(PatchLine.Type.ADD, hl.removePrefix("+")); newLineCount++ }
                hl.startsWith("-") -> newLines += PatchLine(PatchLine.Type.REMOVE, hl.removePrefix("-"))
                hl.startsWith(" ") -> { newLines += PatchLine(PatchLine.Type.CONTEXT, hl.removePrefix(" ")); newLineCount++ }
                hl.startsWith("\\") -> {}
            }
            i++
        }
        val oldCount = numberedMatch?.let { it.groupValues[2].takeIf { str -> str.isNotEmpty() }?.toInt() ?: 1 }
            ?: (oldEnd - oldStart + 1)
        val hunk = PatchHunk(oldStart, oldStart + oldCount, newStart, newStart + newLineCount)
        newLines.forEach(hunk::addLine)
        hunks += hunk
    }
    return hunks.takeIf { it.isNotEmpty() }
}

private fun findContextStart(baseLines: List<String>, patchLines: List<String>, hunkStart: Int): Pair<Int, Int>? {
    val ctx = mutableListOf<String>()
    for (j in hunkStart until patchLines.size) {
        val l = patchLines[j]
        if (l.startsWith("@@") || l.startsWith("***")) break
        when {
            l.startsWith(" ") -> ctx += l.removePrefix(" ")
            l.startsWith("-") -> ctx += l.removePrefix("-")
            l.startsWith("+") -> break
        }
    }
    if (ctx.isEmpty()) return null
    for (b in baseLines.indices) {
        if (b + ctx.size > baseLines.size) break
        if (ctx.indices.all { baseLines[b + it] == ctx[it] }) {
            return b to (b + ctx.size)
        }
    }
    return null
}
