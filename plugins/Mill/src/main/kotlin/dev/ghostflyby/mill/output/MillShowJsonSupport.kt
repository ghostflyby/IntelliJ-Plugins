/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 *
 * This file is part of IntelliJ-Plugins by ghostflyby
 *
 * IntelliJ-Plugins by ghostflyby is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3.0 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, see
 * <https://www.gnu.org/licenses/>.
 */

package dev.ghostflyby.mill.output

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

internal object MillShowJsonSupport {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parseStringList(output: String): MillShowStringListOutput? {
        val element = parseJsonOutput(output) ?: return null
        return parseStringList(element)
    }

    fun parseStringValue(output: String): MillShowStringOutput? {
        val element = parseJsonOutput(output) ?: return null
        return parseStringValue(element)
    }

    fun parseStringList(element: JsonElement): MillShowStringListOutput? {
        val values = runCatching {
            json.decodeFromJsonElement(ListSerializer(String.serializer()), element)
        }.getOrNull() ?: return null
        return MillShowStringListOutput(values)
    }

    fun parseStringValue(element: JsonElement): MillShowStringOutput? {
        val primitive = element as? JsonPrimitive ?: return null
        return MillShowStringOutput(primitive.content)
    }

    internal fun parseJsonOutput(output: String): JsonElement? {
        val trimmed = output.trim()
        if (trimmed.isEmpty()) {
            return null
        }
        return runCatching { json.parseToJsonElement(trimmed) }.getOrNull()
    }
}
