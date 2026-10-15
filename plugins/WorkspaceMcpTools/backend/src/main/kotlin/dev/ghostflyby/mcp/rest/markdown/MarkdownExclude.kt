/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.rest.markdown

/**
 * Excludes a property from the YAML frontmatter while keeping it in the JSON body. Use this when a
 * property is part of the JSON contract but should not appear in the Markdown rendering. (For
 * properties that should be absent from JSON too, use `kotlinx.serialization.Transient` — the
 * renderer treats both as frontmatter-excluded.)
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
internal annotation class MarkdownExclude
