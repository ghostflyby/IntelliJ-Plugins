/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.rest.markdown

import dev.ghostflyby.mcp.filecontent.FileStructure
import dev.ghostflyby.mcp.filecontent.StructureElement
import dev.ghostflyby.mcp.rest.RestJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.serializer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.full.findAnnotation

@Serializable
private data class TableRow(val id: String, val name: String)

@Serializable
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
    @MarkdownExclude val language: String = "",
    @MarkdownExclude val format: String? = null,
)

internal class MarkdownDocumentRendererTest {

    private fun render(value: AllBlocks): String =
        MarkdownDocumentRenderer.render(RestJson.encodeToJsonElement(value), AllBlocks::class)

    private fun renderRows(rows: List<TableRow>): String =
        MarkdownDocumentRenderer.render(
            RestJson.encodeToJsonElement(ListSerializer(serializer<TableRow>()), rows),
            List::class,
            TableRow::class,
        )

    @Test
    fun `frontmatter holds unmarked props and code fence carries language`() {
        val md = render(
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
        // Excluded siblings must not appear in frontmatter.
        assertFalse(md.contains("language:"), md)
        assertFalse(md.contains("format:"), md)
    }

    @Test
    fun `code fence is suppressed when skip gate matches`() {
        val md = render(
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
            listOf(
                StructureElement(
                    name = "Foo", type = "class",
                    children = listOf(StructureElement(name = "bar", type = "fun")),
                ),
            ),
        )
        val md = render(AllBlocks("s", null, structure, null, null))
        assertTrue(md.contains("## Structure\nFoo (class)\n\tbar (fun)\n"), md)
    }

    @Test
    fun `prefix block compresses directory prefixes`() {
        val md = render(AllBlocks("p", null, null, listOf("RootFile.kt", "nested/NestedFile.kt"), null))
        assertTrue(md.contains("@ \nRootFile.kt\n@ nested/\nNestedFile.kt\n"), md)
    }

    @Test
    fun `nested table uses element property names as columns`() {
        val md = render(AllBlocks("t", null, null, null, listOf(TableRow("1", "a"), TableRow("2", "b"))))
        assertTrue(md.contains("| id | name |"), md)
        assertTrue(md.contains("| --- | --- |"), md)
        assertTrue(md.contains("| 1 | a |"), md)
    }

    @Test
    fun `top level list renders as table`() {
        val md = renderRows(listOf(TableRow("1", "a")))
        assertTrue(md.startsWith("| id | name |"), md)
        assertTrue(md.contains("| 1 | a |"), md)
    }

    @Test
    fun `empty top level list still emits header via element type`() {
        val md = renderRows(emptyList())
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
