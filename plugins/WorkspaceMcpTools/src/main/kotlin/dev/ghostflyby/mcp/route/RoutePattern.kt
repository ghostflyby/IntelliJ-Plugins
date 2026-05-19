/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.route

/**
 * Mini Grammar route pattern.
 *
 * Path tokens support:
 * - `literal`         — exact segment match
 * - `{param}`         — single path segment capture
 * - `{+tailParam}`    — RFC 6570 reserved expansion syntax, captured as trailing multi-segment text
 *
 * Query tokens support:
 * - `key={param}`     — capture query param
 * - `key=value`       — literal query param match (reserved for future use)
 */
internal sealed interface PathToken {
    /** The original token text as written in the pattern. */
    val text: String
}

internal data class LiteralToken(override val text: String) : PathToken
internal data class ParamToken(override val text: String, val name: String) : PathToken
internal data class ReservedParamToken(override val text: String, val name: String) : PathToken

internal data class QueryToken(
    val key: String,
    /** null when literal value; non-null when `key={param}` capture. */
    val paramName: String? = null,
    /** Non-null when literal `key=value` match. */
    val literalValue: String? = null,
    /** True when the query param is optional (`{?param}` syntax). */
    val optional: Boolean = false,
)

/**
 * A parsed Ktor-like route pattern.
 *
 * @param original  the raw pattern string (e.g. "projects/{projectKey}")
 * @param pathTokens  parsed path tokens, in order
 * @param queryTokens parsed query tokens, in order
 */
internal data class RoutePattern(
    val original: String,
    val pathTokens: List<PathToken>,
    val queryTokens: List<QueryToken> = emptyList(),
) {
    val hasReservedParam: Boolean
        get() = pathTokens.any { it is ReservedParamToken }

    val paramNames: List<String>
        get() = pathTokens.mapNotNull { token ->
            when (token) {
                is ParamToken -> token.name
                is ReservedParamToken -> token.name
                is LiteralToken -> null
            }
        }

    /**
     * Generate the query template string (e.g. `?key={paramName}&limit=10`)
     * from [queryTokens]. Empty if no query tokens.
     */
    val queryTemplate: String
        get() {
            if (queryTokens.isEmpty()) return ""
            return queryTokens.joinToString("&", prefix = "?") { token ->
                when {
                    token.paramName != null -> "${token.key}={${token.paramName}}"
                    token.literalValue != null -> "${token.key}=${token.literalValue}"
                    else -> token.key
                }
            }
        }

    companion object {
        /** Allowed characters in a variable name. */
        private val PARAM_NAME_RE = Regex("^[a-zA-Z_][a-zA-Z0-9_]*$")

        /**
         * Parse a pattern string into [RoutePattern].
         *
         * Format: `path/part/{param}/{+tail}`.
         *
         * `{+tail}` uses RFC 6570 reserved expansion syntax. This mini
         * grammar currently constrains it to the last path segment and treats
         * it as a single captured string instead of implementing full RFC 6570
         * expansion semantics.
         *
         * @throws IllegalArgumentException if the pattern is invalid.
         */
        fun parse(pattern: String): RoutePattern {
            // Split at '{?' (optional query marker) or literal '?'
            val optQueryIdx = pattern.indexOf("{?")
            val litQueryIdx = pattern.indexOf('?')
            val queryIdx = if (optQueryIdx >= 0) {
                // {?param} present: path is before it, query starts from {?
                optQueryIdx
            } else if (litQueryIdx >= 0) {
                litQueryIdx
            } else {
                -1
            }
            val pathPart = if (queryIdx >= 0) pattern.substring(0, queryIdx) else pattern
            val queryPart = if (queryIdx >= 0) pattern.substring(queryIdx) else ""

            val pathTokens = parsePath(pathPart)
            val queryTokens = if (queryPart.isBlank()) emptyList() else parseQuery(queryPart)

            return RoutePattern(
                original = pattern,
                pathTokens = pathTokens,
                queryTokens = queryTokens,
            )
        }

        private fun parsePath(path: String): List<PathToken> {
            val parts = path.trim('/').split('/')
            val tokens = mutableListOf<PathToken>()

            parts.forEachIndexed { index, part ->
                val token = when {
                    part.startsWith("{+") && part.endsWith("}") -> {
                        val name = part.substring(2, part.length - 1)
                        require(name.isNotBlank()) { "Reserved param name must not be empty in '$part'" }
                        require(PARAM_NAME_RE.matches(name)) {
                            "Invalid reserved param name '$name' in pattern '$path'"
                        }
                        require(index == parts.size - 1) {
                            "Reserved param '{+$name}' must be the last path segment in '$path'"
                        }
                        ReservedParamToken(part, name)
                    }

                    part.startsWith("{") && part.endsWith("}") -> {
                        val name = part.substring(1, part.length - 1)
                        require(name.isNotBlank()) { "Param name must not be empty in '$part'" }
                        require(PARAM_NAME_RE.matches(name)) {
                            "Invalid param name '$name' in pattern '$path'"
                        }
                        ParamToken(part, name)
                    }

                    else -> LiteralToken(part)
                }
                tokens.add(token)
            }

            return tokens
        }

        private fun parseQuery(query: String): List<QueryToken> {
            val trimmed = query.trimStart('?')
            if (trimmed.startsWith("{?")) {
                // {?param} — optional query, no literal key=
                val name = trimmed.substring(2, trimmed.length - 1)
                require(name.isNotBlank()) { "Query param name must not be empty" }
                require(PARAM_NAME_RE.matches(name)) { "Invalid query param name '$name'" }
                return listOf(QueryToken(key = name, paramName = name, optional = true))
            }
            return trimmed.split('&').filter { it.isNotBlank() }.map { pair ->
                val eqIdx = pair.indexOf('=')
                if (eqIdx < 0) {
                    // Literal key with no value
                    QueryToken(key = pair, literalValue = null)
                } else {
                    val key = pair.substring(0, eqIdx)
                    val value = pair.substring(eqIdx + 1)
                    if (value.startsWith("{?") && value.endsWith("}")) {
                        val name = value.substring(2, value.length - 1)
                        require(name.isNotBlank()) { "Query param name must not be empty in '$pair'" }
                        require(PARAM_NAME_RE.matches(name)) {
                            "Invalid query param name '$name' in '$pair'"
                        }
                        QueryToken(key = key, paramName = name, optional = true)
                    } else if (value.startsWith("{") && value.endsWith("}")) {
                        val name = value.substring(1, value.length - 1)
                        require(name.isNotBlank()) { "Query param name must not be empty in '$pair'" }
                        require(PARAM_NAME_RE.matches(name)) {
                            "Invalid query param name '$name' in '$pair'"
                        }
                        QueryToken(key = key, paramName = name)
                    } else {
                        QueryToken(key = key, literalValue = value)
                    }
                }
            }
        }
    }
}
