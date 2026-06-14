/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.server.tools

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class CamelToSnakeCaseTest {
    @Test
    fun `camelCase converts to snake_case`() {
        assertEquals("do_thing", "doThing".camelToSnakeCase())
    }

    @Test
    fun `PascalCase converts to snake_case`() {
        assertEquals("do_thing", "DoThing".camelToSnakeCase())
    }

    @Test
    fun `all lowercase stays unchanged`() {
        assertEquals("file_write", "file_write".camelToSnakeCase())
    }

    @Test
    fun `consecutive uppercase abbreviations`() {
        assertEquals("pdf_reader", "PDFReader".camelToSnakeCase())
    }

    @Test
    fun `mixed with numbers`() {
        assertEquals("read_xml_file", "readXMLFile".camelToSnakeCase())
    }

    @Test
    fun `single word stays lowercase`() {
        assertEquals("hello", "hello".camelToSnakeCase())
    }

    @Test
    fun `leading uppercase single word`() {
        assertEquals("hello", "Hello".camelToSnakeCase())
    }
}
