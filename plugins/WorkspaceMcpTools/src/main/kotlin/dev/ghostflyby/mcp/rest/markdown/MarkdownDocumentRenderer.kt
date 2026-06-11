/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.rest.markdown

import dev.ghostflyby.mcp.rest.fencedCode
import dev.ghostflyby.mcp.rest.renderPrefixBlock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Transient
import kotlinx.serialization.json.*
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.javaField

/**
 * Renders a kotlinx [JsonElement] (the same tree used for the JSON response) into a Markdown document:
 * YAML frontmatter for unmarked properties plus body blocks for properties annotated with
 * [MarkdownBlock]. A top-level [JsonArray] renders as a GFM table. Reflection is used only once, to
 * read annotation metadata from the response class; all values come from the JSON tree, guaranteeing
 * the frontmatter and the JSON body stay consistent.
 */
internal object MarkdownDocumentRenderer {

    private val yaml: Yaml = Yaml(
        DumperOptions().apply {
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
            isExplicitStart = false
            isExplicitEnd = false
            splitLines = false
        },
    )

    private sealed interface FieldRole {
        data object Frontmatter : FieldRole
        data object Exclude : FieldRole
        data class Block(val spec: MarkdownBlock) : FieldRole
    }

    /**
     * @param tree the JSON tree of the response value (object or array)
     * @param type the response value's class, used once to read annotation metadata
     * @param elementType element class for a top-level array (recovers columns when empty)
     */
    fun render(tree: JsonElement, type: KClass<*>, elementType: KClass<*>? = null): String = when (tree) {
        is JsonArray -> renderTable(tree, elementType)
        is JsonObject -> renderObject(tree, type)
        else -> yamlFrontMatter(tree)
    }

    // ── single object ───────────────────────────────────────

    private fun renderObject(tree: JsonObject, type: KClass<*>): String {
        val (roles, kotlinToJson) = fieldRoles(type)
        val frontMatter =
            JsonObject(tree.filterKeys { roles[it] !is FieldRole.Block && roles[it] !is FieldRole.Exclude })
        val bodyBlocks = orderedPropertyNames(type, kotlinToJson).mapNotNull { name ->
            val role = roles[name] as? FieldRole.Block ?: return@mapNotNull null
            renderBodyBlock(role.spec, tree, name, kotlinToJson)
        }
        return buildString {
            append(yamlFrontMatter(frontMatter))
            for (rendered in bodyBlocks) {
                if (isNotEmpty()) appendLine()
                append(rendered)
            }
        }
    }

    private fun renderBodyBlock(
        block: MarkdownBlock,
        tree: JsonObject,
        key: String,
        kotlinToJson: Map<String, String>,
    ): String? {
        val raw = tree[key]?.takeUnless { it is JsonNull } ?: return null
        if (block.skipWhenProperty.isNotEmpty()) {
            val gate = (tree[kotlinToJson[block.skipWhenProperty] ?: block.skipWhenProperty] as? JsonPrimitive)?.content
            if (gate == block.skipWhenEquals) return null
        }
        return when (block.kind) {
            BlockKind.CODE_FENCE -> {
                val language = block.languageProperty
                    .takeIf { it.isNotEmpty() }
                    ?.let { (tree[kotlinToJson[it] ?: it] as? JsonPrimitive)?.content }
                    .orEmpty()
                fencedCode((raw as JsonPrimitive).content, language)
            }

            BlockKind.STRUCTURE_TREE -> buildString {
                if (block.heading.isNotEmpty()) appendLine(block.heading)
                append(renderStructureTreeFromJson(raw))
            }

            BlockKind.PREFIX_BLOCK -> renderPrefixBlock(raw.jsonArray.map { (it as JsonPrimitive).content })

            BlockKind.TABLE -> renderTable(raw.jsonArray, null)
        }
    }

    // ── top-level / nested table ────────────────────────────

    private fun renderTable(rows: JsonArray, elementType: KClass<*>?): String {
        val columns = (rows.firstOrNull() as? JsonObject)?.keys?.toList()
            ?: elementType?.let { serializedPropertyNames(it) }
            ?: emptyList()
        return buildString {
            appendLine("| ${columns.joinToString(" | ")} |")
            appendLine("| ${columns.joinToString(" | ") { "---" }} |")
            for (row in rows) {
                val obj = row as? JsonObject ?: continue
                val cells = columns.map { col -> (obj[col] as? JsonPrimitive)?.content.orEmpty() }
                appendLine("| ${cells.joinToString(" | ")} |")
            }
        }
    }

    // ── annotation scan (the only reflection) ───────────────

    /**
     * Returns the serialized (JSON) name for a property, respecting [SerialName].
     */
    private fun serializedName(prop: KProperty1<out Any, *>): String {
        val ann = prop.findAnnotation<SerialName>()
            ?: prop.javaField?.getAnnotation(SerialName::class.java)
            ?: prop.getter.findAnnotation<SerialName>()
        return ann?.value ?: prop.name
    }

    private fun fieldRoles(type: KClass<*>): Pair<Map<String, FieldRole>, Map<String, String>> {
        val kotlinToJson = type.memberProperties.associate { prop ->
            prop.name to serializedName(prop)
        }
        val roles = type.memberProperties.associate { prop ->
            val jsonName = kotlinToJson[prop.name]!!
            val block = prop.findAnnotation<MarkdownBlock>()
            val role = when {
                block != null -> FieldRole.Block(block)
                isFrontmatterExcluded(prop) -> FieldRole.Exclude
                else -> FieldRole.Frontmatter
            }
            jsonName to role
        }
        return roles to kotlinToJson
    }

    private fun orderedPropertyNames(type: KClass<*>, kotlinToJson: Map<String, String>): List<String> =
        type.primaryConstructor?.parameters?.mapNotNull { param ->
            kotlinToJson[param.name] ?: param.name
        } ?: kotlinToJson.values.toList()

    /** Property names in declaration order, resolved to serialized names (no pre-computed map). */
    private fun serializedPropertyNames(type: KClass<*>): List<String> =
        type.primaryConstructor?.parameters?.mapNotNull { param ->
            val prop = type.memberProperties.firstOrNull { it.name == param.name }
            prop?.let { serializedName(it) } ?: param.name
        } ?: type.memberProperties.map { serializedName(it) }

    /**
     * A property is excluded from the frontmatter when marked [Transient] (also absent from JSON) or
     * [MarkdownExclude] (frontmatter-only exclusion). The `-Xannotation-default-target=param-property`
     * compiler arg routes such annotations to the backing field, where [findAnnotation] on the Kotlin
     * property cannot see them, so check the property, its backing field, and its getter.
     */
    private fun isFrontmatterExcluded(prop: KProperty1<out Any, *>): Boolean {
        if (prop.findAnnotation<Transient>() != null || prop.findAnnotation<MarkdownExclude>() != null) return true
        prop.javaField?.let { field ->
            if (field.isAnnotationPresent(Transient::class.java) ||
                field.isAnnotationPresent(MarkdownExclude::class.java)
            ) {
                return true
            }
        }
        return prop.getter.findAnnotation<Transient>() != null || prop.getter.findAnnotation<MarkdownExclude>() != null
    }


    private fun yamlFrontMatter(tree: JsonElement): String {
        val body = yaml.dump(toJavaValue(tree)).trimEnd()
        return buildString {
            appendLine("---")
            if (body.isNotEmpty()) appendLine(body)
            appendLine("---")
        }
    }

    private fun toJavaValue(element: JsonElement): Any? = when (element) {
        is JsonNull -> null
        is JsonObject -> LinkedHashMap<String, Any?>(element.size).apply {
            element.forEach { (k, v) -> put(k, toJavaValue(v)) }
        }

        is JsonArray -> element.map { toJavaValue(it) }
        is JsonPrimitive ->
            if (element.isString) element.content
            else element.booleanOrNull ?: element.longOrNull ?: element.doubleOrNull ?: element.content
    }
    // ── structure tree from JSON tree ───────────────────────

    /**
     * Renders a [FileStructure] directly from its JSON subtree, avoiding a deserialize roundtrip.
     */
    private fun renderStructureTreeFromJson(element: JsonElement): String = buildString {
        val elements = (element as? JsonObject)?.get("elements") as? JsonArray ?: return@buildString
        for (child in elements) {
            appendStructureElementFromJson(child as JsonObject, 0)
        }
    }

    private fun StringBuilder.appendStructureElementFromJson(element: JsonObject, depth: Int) {
        repeat(depth) { append('\t') }
        append((element["name"] as? JsonPrimitive)?.content.orEmpty())
        val type = (element["type"] as? JsonPrimitive)?.content.orEmpty()
        if (type.isNotBlank()) append(" (").append(type).append(")")
        appendLine()
        val children = element["children"] as? JsonArray ?: return
        for (child in children) {
            appendStructureElementFromJson(child as JsonObject, depth + 1)
        }
    }

}
