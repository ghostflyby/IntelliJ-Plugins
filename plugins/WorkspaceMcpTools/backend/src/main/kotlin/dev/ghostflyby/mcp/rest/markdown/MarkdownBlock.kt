/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.rest.markdown

/**
 * Marks a model property as a markdown body block instead of a YAML frontmatter entry.
 *
 * Unannotated properties of a response model become frontmatter; annotated ones are peeled
 * out and rendered as body blocks in declaration order after the frontmatter.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
internal annotation class MarkdownBlock(
    val kind: BlockKind,
    /** Sibling String property whose value is the code-fence language tag (CODE_FENCE only). */
    val languageProperty: String = "",
    /** Heading line emitted before the block, e.g. "## Structure" (STRUCTURE_TREE). */
    val heading: String = "",
    /** Sibling property name; when its value equals [skipWhenEquals] the block is suppressed. */
    val skipWhenProperty: String = "",
    val skipWhenEquals: String = "",
)

internal enum class BlockKind {
    CODE_FENCE,
    STRUCTURE_TREE,
    PREFIX_BLOCK,
    TABLE,
}
