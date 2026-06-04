package dev.ghostflyby.mcp.filecontent

import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile

internal class WorkspaceGlobPattern private constructor(
    private val segments: List<List<Token>>,
    internal val literalFileName: String?,
    internal val extension: String?,
    internal val recursive: Boolean,
) {
    internal fun matches(relativePath: String): Boolean {
        val pathSegments = normalizePath(relativePath)
        return matches(segmentIndex = 0, pathIndex = 0, pathSegments = pathSegments)
    }

    private fun matches(segmentIndex: Int, pathIndex: Int, pathSegments: List<String>): Boolean {
        if (segmentIndex == segments.size) return pathIndex == pathSegments.size
        val segment = segments[segmentIndex]
        if (segment.singleOrNull() == Token.GlobStar) {
            for (nextPathIndex in pathIndex..pathSegments.size) {
                if (matches(segmentIndex + 1, nextPathIndex, pathSegments)) return true
            }
            return false
        }
        if (pathIndex >= pathSegments.size) return false
        return matchesSegment(segment, pathSegments[pathIndex]) &&
                matches(segmentIndex + 1, pathIndex + 1, pathSegments)
    }

    private fun matchesSegment(tokens: List<Token>, text: String): Boolean {
        val dp = Array(tokens.size + 1) { BooleanArray(text.length + 1) }
        dp[0][0] = true
        for (tokenIndex in tokens.indices) {
            when (val token = tokens[tokenIndex]) {
                Token.Star -> {
                    for (textIndex in 0..text.length) {
                        if (dp[tokenIndex][textIndex]) {
                            for (nextTextIndex in textIndex..text.length) {
                                dp[tokenIndex + 1][nextTextIndex] = true
                            }
                        }
                    }
                }

                Token.Question -> {
                    for (textIndex in 0 until text.length) {
                        if (dp[tokenIndex][textIndex]) dp[tokenIndex + 1][textIndex + 1] = true
                    }
                }

                is Token.Literal -> {
                    for (textIndex in 0 until text.length) {
                        if (dp[tokenIndex][textIndex] && text[textIndex] == token.value) {
                            dp[tokenIndex + 1][textIndex + 1] = true
                        }
                    }
                }

                Token.GlobStar -> error("** is only valid as a complete path segment")
            }
        }
        return dp[tokens.size][text.length]
    }

    internal fun relativePath(file: VirtualFile, root: VirtualFile): String? =
        VfsUtilCore.getRelativePath(file, root, '/')

    private sealed interface Token {
        data object Star : Token
        data object Question : Token
        data object GlobStar : Token
        data class Literal(val value: Char) : Token
    }

    internal companion object {
        internal fun compile(rawPattern: String): WorkspaceGlobPattern {
            val normalized = rawPattern.trim()
            require(normalized.isNotEmpty()) { "Glob pattern must not be blank" }
            require(!normalized.startsWith('/')) { "Glob pattern must be relative: $rawPattern" }
            val parts = normalizePath(normalized)
            require(parts.none { it == ".." }) { "Glob pattern must not contain '..' segments: $rawPattern" }
            val segments = parts.map { parseSegment(it, rawPattern) }
            return WorkspaceGlobPattern(
                segments = segments,
                literalFileName = literalFileName(parts, segments),
                extension = extension(parts, segments),
                recursive = segments.any { it.singleOrNull() == Token.GlobStar },
            )
        }

        private fun normalizePath(path: String): List<String> =
            path.split('/').filter { it.isNotEmpty() && it != "." }

        private fun parseSegment(segment: String, rawPattern: String): List<Token> {
            if (segment == "**") return listOf(Token.GlobStar)
            require(!segment.contains("**")) { "'**' must be a complete path segment: $rawPattern" }
            val tokens = mutableListOf<Token>()
            var index = 0
            while (index < segment.length) {
                when (val char = segment[index]) {
                    '*' -> tokens += Token.Star
                    '?' -> tokens += Token.Question
                    '\\' -> {
                        index++
                        require(index < segment.length) { "Dangling escape in glob pattern: $rawPattern" }
                        tokens += Token.Literal(segment[index])
                    }

                    else -> tokens += Token.Literal(char)
                }
                index++
            }
            return tokens
        }

        private fun literalFileName(parts: List<String>, segments: List<List<Token>>): String? {
            val lastSegment = segments.lastOrNull() ?: return null
            if (lastSegment.any { it !is Token.Literal }) return null
            return literalText(lastSegment)
        }

        private fun extension(parts: List<String>, segments: List<List<Token>>): String? {
            val lastPart = parts.lastOrNull() ?: return null
            val lastSegment = segments.lastOrNull() ?: return null
            if (lastSegment.any { it is Token.GlobStar }) return null
            if (lastSegment.all { it is Token.Literal }) return lastPart.substringAfterLast(
                '.',
                missingDelimiterValue = "",
            )
                .takeIf { it.isNotBlank() }
            val dotIndex = lastSegment.indexOfLast { it == Token.Literal('.') }
            if (dotIndex == -1) return null
            val suffix = lastSegment.drop(dotIndex + 1)
            if (suffix.any { it !is Token.Literal }) return null
            return suffix
                .joinToString("") { (it as Token.Literal).value.toString() }
                .takeIf { it.isNotBlank() }
        }

        private fun literalText(tokens: List<Token>): String =
            tokens.joinToString("") { (it as Token.Literal).value.toString() }
    }
}
