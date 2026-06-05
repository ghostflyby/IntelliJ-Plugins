package dev.ghostflyby.skills

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml

internal class SkillMdInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        return SkillMdVisitor(holder)
    }

    private class SkillMdVisitor(private val holder: ProblemsHolder) : PsiElementVisitor() {

        private val yaml = Yaml(LoaderOptions())

        override fun visitFile(file: PsiFile) {
            if (file.name != "SKILL.md") return
            val virtualFile = file.virtualFile
            checkSkillMd(virtualFile, file, holder)
        }

        private fun checkSkillMd(vf: VirtualFile?, psi: PsiFile, holder: ProblemsHolder) {
            val text = psi.text
            if (text.isBlank()) return

            val frontMatter = parseFrontMatter(text) ?: return
            val (yamlText, delimiterStart, delimiterEnd) = frontMatter
            val range = TextRange(delimiterStart, delimiterEnd)

            val parsed = try {
                yaml.load<Map<String, Any?>>(yamlText)
            } catch (e: Exception) {
                holder.registerProblem(
                    psi, range,
                    SkillMdBundle.message("skill.md.error.invalid.yaml", e.message ?: "parse error")
                )
                return
            }

            if (parsed == null) {
                holder.registerProblem(
                    psi, range,
                    SkillMdBundle.message("skill.md.error.missing.name")
                )
                return
            }

            val name = parsed["name"] as? String
            if (name.isNullOrBlank()) {
                holder.registerProblem(
                    psi, range,
                    SkillMdBundle.message("skill.md.error.missing.name"),
                    FixSkillNameQuickFix("my-new-skill")
                )
                return
            }

            val description = parsed["description"] as? String
            if (description.isNullOrBlank()) {
                holder.registerProblem(
                    psi, range,
                    SkillMdBundle.message("skill.md.error.missing.description")
                )
            }

            if (!isKebabCase(name)) {
                holder.registerProblem(
                    psi, range,
                    SkillMdBundle.message("skill.md.error.name.not.kebab", name),
                    FixSkillNameQuickFix(toKebabCase(name))
                )
            }

            val parentDir = vf?.parent?.name
            if (parentDir != null && parentDir != name) {
                holder.registerProblem(
                    psi, range,
                    SkillMdBundle.message("skill.md.error.name.mismatch", name, parentDir),
                    MatchDirectoryNameQuickFix(parentDir)
                )
            }
        }

        private fun parseFrontMatter(text: String): Triple<String, Int, Int>? {
            val lines = text.lines()
            if (lines.size < 3) return null

            val firstLine = lines.first()
            if (firstLine.trim() != "---") return null

            var endIdx = -1
            var offset = firstLine.length + 1
            for (i in 1 until lines.size) {
                val line = lines[i]
                if (line.trim() == "---") {
                    endIdx = i
                    break
                }
                offset += line.length + 1
            }

            if (endIdx < 0) return null

            val yamlLines = lines.subList(1, endIdx)
            return Triple(
                yamlLines.joinToString("\n"),
                firstLine.length,
                offset
            )
        }

        private fun isKebabCase(s: String): Boolean {
            return KEbabCaseRegex.matches(s)
        }

        private fun toKebabCase(s: String): String {
            return s.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        }
    }

    companion object {
        private val KEbabCaseRegex = Regex("^[a-z][a-z0-9]*(-[a-z0-9]+)*$")
    }
}
