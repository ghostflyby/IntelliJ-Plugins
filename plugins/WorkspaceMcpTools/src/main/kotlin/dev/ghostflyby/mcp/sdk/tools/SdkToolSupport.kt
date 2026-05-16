/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.sdk.tools

import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberExtensionProperties

internal interface WorkspaceMcpProjectToolArguments {
    val projectKey: String?
    val projectPath: String?
}

internal val toolArgsJson: Json = Json {
    ignoreUnknownKeys = false
    explicitNulls = false
}

private val jsonSchemaCache = mutableMapOf<KClass<*>, JsonObject>()

private fun loadJsonSchema(kClass: KClass<*>): JsonObject = jsonSchemaCache.getOrPut(kClass) {
    @Suppress("UNCHECKED_CAST")
    val prop = kClass.memberExtensionProperties
        .firstOrNull { it.name == "jsonSchema" }
        ?: error("jsonSchema extension not generated for ${kClass.qualifiedName}")
    (prop as KProperty1<KClass<*>, *>).get(kClass) as JsonObject
}

internal inline fun <reified T : Any> schemaFor(): ToolSchema {
    val jsonObj = loadJsonSchema(T::class)
    return ToolSchema(
        properties = jsonObj["properties"] as? JsonObject,
        required = (jsonObj["required"] as? JsonArray)?.map { it.jsonPrimitive.content },
    )
}
