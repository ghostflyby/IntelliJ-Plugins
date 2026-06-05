package dev.ghostflyby.skills

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile

internal class SkillFrontmatterStructureInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        return object : PsiElementVisitor() {
            override fun visitFile(file: PsiFile) {
                val result = SkillFrontmatterAnalyzer.analyze(file)
                val header = result.frontMatterHeader ?: return
                val range = result.range ?: return

                // delimiter already handled by Markdown parser — if we have a header, delimiters are fine

                // YAML parse error
                if (result.parseError != null) {
                    holder.registerProblem(
                        header, range,
                        SkillMdBundle.message("structure.error.invalid.yaml", result.parseError),
                    )
                    return
                }

                // Top level must be a mapping
                if (result.parsed == null) {
                    holder.registerProblem(
                        header, range,
                        SkillMdBundle.message("structure.error.not.mapping"),
                    )
                    return
                }

                // Unknown fields
                for (key in result.parsed.keys) {
                    if (key !in KNOWN_FRONTMATTER_FIELDS) {
                        holder.registerProblem(
                            header, range,
                            SkillMdBundle.message("structure.error.unknown.field", key),
                        )
                    }
                }

                // Field type checks
                for (key in result.parsed.keys) {
                    val value = result.parsed[key]
                    if (key == "metadata") continue // metadata is object, not string
                    if (key == "allowed-tools") {
                        if (value != null && value !is String) {
                            holder.registerProblem(
                                header, range,
                                SkillMdBundle.message("structure.error.field.not.string", key, value.javaClass.simpleName),
                            )
                        }
                        continue
                    }
                    if (value != null && value !is String) {
                        holder.registerProblem(
                            header, range,
                            SkillMdBundle.message("structure.error.field.not.string", key, value.javaClass.simpleName),
                        )
                    }
                }
            }
        }
    }
}
