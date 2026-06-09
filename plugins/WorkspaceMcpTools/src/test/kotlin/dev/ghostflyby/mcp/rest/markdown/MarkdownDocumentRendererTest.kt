/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.rest.markdown

import com.fasterxml.jackson.annotation.JsonIgnore
import dev.ghostflyby.mcp.filecontent.FileStructure
import dev.ghostflyby.mcp.filecontent.StructureElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.typeOf

private data class TableRow(val id: String, val name: String)

private data class AllBlocks(
    val title: String,
    @MarkdownBlock(
        BlockKind.CODE_FENCE,
        languageProperty = "language",
        skipWhenProperty = "format",
        skipWhenEquals = "base64",
    )
    val content: String?,
    @MarkdownBlock(BlockKind.STRUCTURE_TREE, heading = "## Structure")
    val structure: FileStructure?,
    @MarkdownBlock(BlockKind.PREFIX_BLOCK)
    val paths: List<String>?,
    @MarkdownBlock(BlockKind.TABLE)
    val rows: List<TableRow>?,
    @JsonIgnore val language: String = "",
    @JsonIgnore val format: String? = null,
)

internal class MarkdownDocumentRendererTest {

    @Test
    fun `frontmatter holds unmarked props and code fence carries language`() {
        val md = MarkdownDocumentRenderer.render(
            AllBlocks(
                title = "hello",
                content = "fun main() {}",
                structure = null,
                paths = null,
                rows = null,
                language = "kotlin",
            ),
        )
        assertTrue(md.startsWith("---\n"), md)
        assertTrue(md.contains("title: hello"), md)
        assertTrue(md.contains("```kotlin\nfun main() {}\n```"), md)
        // Jackson-ignored siblings must not appear in frontmatter.
        assertFalse(md.contains("language:"), md)
        assertFalse(md.contains("format:"), md)
    }

    @Test
    fun `code fence is suppressed when skip gate matches`() {
        val md = MarkdownDocumentRenderer.render(
            AllBlocks(
                title = "bin",
                content = "QUJD",
                structure = null,
                paths = null,
                rows = null,
                format = "base64",
            ),
        )
        assertFalse(md.contains("```"), md)
    }

    @Test
    fun `structure tree renders heading and indented tree`() {
        val structure = FileStructure(
            listOf(StructureElement(name = "Foo", type = "class", children = listOf(
                StructureElement(name = "bar", type = "fun"),
            ))),
        )
        val md = MarkdownDocumentRenderer.render(
            AllBlocks("s", null, structure, null, null),
        )
        assertTrue(md.contains("## Structure\nFoo (class)\n\tbar (fun)\n"), md)
    }

    @Test
    fun `prefix block compresses directory prefixes`() {
        val md = MarkdownDocumentRenderer.render(
            AllBlocks("p", null, null, listOf("RootFile.kt", "nested/NestedFile.kt"), null),
        )
        assertTrue(md.contains("@ \nRootFile.kt\n@ nested/\nNestedFile.kt\n"), md)
    }

    @Test
    fun `nested table uses element property names as columns`() {
        val md = MarkdownDocumentRenderer.render(
            AllBlocks("t", null, null, null, listOf(TableRow("1", "a"), TableRow("2", "b"))),
        )
        assertTrue(md.contains("| id | name |"), md)
        assertTrue(md.contains("| --- | --- |"), md)
        assertTrue(md.contains("| 1 | a |"), md)
    }

    @Test
    fun `top level list renders as table`() {
        val md = MarkdownDocumentRenderer.render(
            listOf(TableRow("1", "a")),
            typeOf<TableRow>(),
        )
        assertTrue(md.startsWith("| id | name |"), md)
        assertTrue(md.contains("| 1 | a |"), md)
    }

    @Test
    fun `empty top level list still emits header via element type`() {
        val md = MarkdownDocumentRenderer.render(emptyList<TableRow>(), typeOf<TableRow>())
        assertTrue(md.startsWith("| id | name |"), md)
    }

    @Test
    fun `markdown block annotation is retained at runtime`() {
        val prop = AllBlocks::class.declaredMemberProperties.first { it.name == "content" }
        val annotation = prop.findAnnotation<MarkdownBlock>()
        assertNotNull(annotation, "MarkdownBlock must be RUNTIME-retained and readable via reflection")
        assertEquals(BlockKind.CODE_FENCE, annotation!!.kind)
    }
}
