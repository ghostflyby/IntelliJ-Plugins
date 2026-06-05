package dev.ghostflyby.skills

import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownFrontMatterHeader
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml

/**
 * Result of parsing a SKILL.md frontmatter.
 * Each inspection consumes this to report problems.
 */
internal data class AnalyzerResult(
    /** The PsiFile */
    val psiFile: PsiFile,
    /** The virtual file, may be null for non-physical files */
    val virtualFile: VirtualFile?,
    /** The MarkdownFrontMatterHeader PSI element, null if not found */
    val frontMatterHeader: MarkdownFrontMatterHeader?,
    /** Parsed YAML as a Map, null if YAML parse failed or not a mapping */
    val parsed: Map<String, Any?>?,
    /** Text range covering the full ---...--- block */
    val range: TextRange?,
    /** Error message if YAML parsing failed */
    val parseError: String?,
)

/**
 * Shared frontmatter analyzer for SKILL.md files.
 * YAML is parsed once; results are consumed by all inspections.
 */
internal object SkillFrontmatterAnalyzer {

    private val yaml = Yaml(LoaderOptions())

    fun analyze(file: PsiFile): AnalyzerResult {
        if (file.name != "SKILL.md") return emptyResult(file)
        val vf = file.virtualFile

        val header = PsiTreeUtil.findChildOfType(file, MarkdownFrontMatterHeader::class.java)
        if (header == null) return emptyResult(file)

        val text = header.text
        val range = header.textRange

        val yamlText = extractYamlText(text) ?: return AnalyzerResult(
            psiFile = file, virtualFile = vf,
            frontMatterHeader = header, parsed = null,
            range = range, parseError = null,
        )

        return try {
            val raw = yaml.load<Any?>(yamlText)
            if (raw is Map<*, *>) {
                @Suppress("UNCHECKED_CAST")
                AnalyzerResult(file, vf, header, raw as Map<String, Any?>, range, null)
            } else {
                AnalyzerResult(file, vf, header, null, range, null)
            }
        } catch (e: Exception) {
            AnalyzerResult(file, vf, header, null, range, e.message ?: "parse error")
        }
    }

    private fun extractYamlText(frontMatterText: String): String? {
        val lines = frontMatterText.lines()
        if (lines.size < 2) return null
        val firstLine = lines.first().trim()
        val lastLine = lines.last().trim()
        val contentLines = when {
            firstLine == "---" && lastLine == "---" && lines.size >= 3 ->
                lines.subList(1, lines.size - 1)
            firstLine == "---" && lines.size == 2 -> emptyList()
            else -> return null
        }
        return contentLines.joinToString("\n")
    }

    private fun emptyResult(file: PsiFile) = AnalyzerResult(
        psiFile = file, virtualFile = file.virtualFile,
        frontMatterHeader = null, parsed = null,
        range = null, parseError = null,
    )
}

/** Helper to get a YAML scalar value as String from the parsed map. */
internal fun Map<String, Any?>.stringField(key: String): String? =
    (this[key] as? String)?.takeIf { it.isNotBlank() }

/** Helper to get a YAML scalar value as String, allowing blank. */
internal fun Map<String, Any?>.rawStringField(key: String): String? =
    this[key] as? String

internal val KNOWN_FRONTMATTER_FIELDS = setOf(
    "name", "description", "license",
    "compatibility", "metadata", "allowed-tools",
)

internal val NAME_REGEX = Regex("""^[a-z0-9]([a-z0-9-]*[a-z0-9])?$""")
internal const val NAME_MAX_LENGTH = 64
internal const val DESCRIPTION_MAX_LENGTH = 1024
internal const val COMPATIBILITY_MAX_LENGTH = 500
