/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.rest.markdown

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator
import com.fasterxml.jackson.module.kotlin.kotlinModule
import dev.ghostflyby.mcp.filecontent.FileStructure
import dev.ghostflyby.mcp.filecontent.renderStructureText
import dev.ghostflyby.mcp.rest.fencedCode
import dev.ghostflyby.mcp.rest.renderPrefixBlock
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.KType
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.isAccessible
import kotlin.reflect.jvm.javaField

/**
 * Renders a response model into a markdown document: YAML frontmatter for unmarked properties
 * plus body blocks for properties annotated with [MarkdownBlock]. A top-level [List] renders as a
 * GFM table. Pure and Ktor-free so it can be unit tested without a server fixture.
 */
internal object MarkdownDocumentRenderer {

    private val YamlMapper: ObjectMapper = ObjectMapper(
        YAMLFactory.builder()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
            .build(),
    )
        .registerModule(kotlinModule())
        .setSerializationInclusion(JsonInclude.Include.ALWAYS)

    /**
     * @param value the response model instance
     * @param elementType the declared element [KType] for a top-level list (recovers columns when empty)
     */
    fun render(value: Any, elementType: KType? = null): String = when (value) {
        is List<*> -> renderTable(value, elementType)
        else -> renderObject(value)
    }

    // ── single object ───────────────────────────────────────

    private fun renderObject(value: Any): String {
        val kClass = value::class
        val props = orderedProperties(kClass)
        val frontMatter = linkedMapOf<String, Any?>()
        val bodyBlocks = mutableListOf<String>()

        for (prop in props) {
            val block = prop.findAnnotation<MarkdownBlock>()
            if (block == null) {
                if (isJsonIgnored(prop)) continue
                frontMatter[prop.name] = readValue(prop, value)
                continue
            }
            renderBodyBlock(block, prop, value)?.let { bodyBlocks += it }
        }

        return buildString {
            append(yamlFrontMatter(frontMatter))
            for (rendered in bodyBlocks) {
                if (isNotEmpty()) appendLine()
                append(rendered)
            }
        }
    }

    private fun renderBodyBlock(block: MarkdownBlock, prop: KProperty1<out Any, *>, owner: Any): String? {
        val raw = readValue(prop, owner) ?: return null
        if (block.skipWhenProperty.isNotEmpty()) {
            val gate = siblingValue(owner, block.skipWhenProperty)?.toString()
            if (gate == block.skipWhenEquals) return null
        }
        return when (block.kind) {
            BlockKind.CODE_FENCE -> {
                val language = block.languageProperty
                    .takeIf { it.isNotEmpty() }
                    ?.let { siblingValue(owner, it)?.toString() }
                    .orEmpty()
                fencedCode(raw.toString(), language)
            }

            BlockKind.STRUCTURE_TREE -> buildString {
                if (block.heading.isNotEmpty()) appendLine(block.heading)
                append(renderStructureText(raw as FileStructure))
            }

            BlockKind.PREFIX_BLOCK -> {
                @Suppress("UNCHECKED_CAST")
                renderPrefixBlock(raw as List<String>)
            }

            BlockKind.TABLE -> renderTable(raw as List<*>, prop.returnType.arguments.firstOrNull()?.type)
        }
    }

    // ── top-level / nested table ────────────────────────────

    private fun renderTable(rows: List<*>, elementType: KType?): String {
        val elementClass: KClass<*>? =
            (rows.firstOrNull { it != null }?.let { it::class })
                ?: (elementType?.classifier as? KClass<*>)
        val columns = elementClass?.let { orderedProperties(it).map { p -> p.name } } ?: emptyList()
        return buildString {
            appendLine("| ${columns.joinToString(" | ")} |")
            appendLine("| ${columns.joinToString(" | ") { "---" }} |")
            for (row in rows) {
                if (row == null) continue
                val cells = orderedProperties(row::class).map { p -> readValue(p, row)?.toString().orEmpty() }
                appendLine("| ${cells.joinToString(" | ")} |")
            }
        }
    }

    // ── reflection helpers ──────────────────────────────────

    /** Properties in primary-constructor declaration order, respecting [JsonPropertyOrder]. */
    private fun orderedProperties(kClass: KClass<*>): List<KProperty1<out Any, *>> {
        val byName = kClass.memberProperties.associateBy { it.name }
        val explicit = kClass.findAnnotation<com.fasterxml.jackson.annotation.JsonPropertyOrder>()?.value
        if (explicit != null && explicit.isNotEmpty()) {
            return explicit.mapNotNull { byName[it] }
        }
        val ctorOrder = kClass.primaryConstructor?.parameters?.mapNotNull { it.name }
        return if (ctorOrder != null) ctorOrder.mapNotNull { byName[it] } else kClass.memberProperties.toList()
    }

    /**
     * The `-Xannotation-default-target=param-property` compiler arg routes `@JsonIgnore` to the
     * backing field, where [findAnnotation] on the Kotlin property cannot see it. Check the
     * property, its backing field, and its getter.
     */
    private fun isJsonIgnored(prop: KProperty1<out Any, *>): Boolean {
        if (prop.findAnnotation<com.fasterxml.jackson.annotation.JsonIgnore>() != null) return true
        if (prop.javaField?.isAnnotationPresent(com.fasterxml.jackson.annotation.JsonIgnore::class.java) == true) return true
        return prop.getter.findAnnotation<com.fasterxml.jackson.annotation.JsonIgnore>() != null
    }

    private fun readValue(prop: KProperty1<out Any, *>, owner: Any): Any? {
        @Suppress("UNCHECKED_CAST")
        val typed = prop as KProperty1<Any, *>
        typed.isAccessible = true
        return typed.get(owner)
    }

    private fun siblingValue(owner: Any, name: String): Any? {
        val prop = owner::class.memberProperties.firstOrNull { it.name == name } ?: return null
        return readValue(prop, owner)
    }

    // ── block renderers ─────────────────────────────────────

    private fun yamlFrontMatter(values: Map<String, Any?>): String {
        val yaml = YamlMapper.writeValueAsString(values).removeSuffix("...\n").trimEnd()
        return buildString {
            appendLine("---")
            if (yaml.isNotEmpty()) appendLine(yaml)
            appendLine("---")
        }
    }

}
