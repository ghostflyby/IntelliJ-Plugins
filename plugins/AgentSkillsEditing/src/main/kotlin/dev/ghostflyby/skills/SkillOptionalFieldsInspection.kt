package dev.ghostflyby.skills

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile

internal class SkillOptionalFieldsInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        return object : PsiElementVisitor() {
            override fun visitFile(file: PsiFile) {
                val result = SkillFrontmatterAnalyzer.analyze(file)
                val header = result.frontMatterHeader ?: return
                val range = result.range ?: return
                val parsed = result.parsed ?: return

                // compatibility: max 500 chars
                val compat = parsed.rawStringField("compatibility")
                if (compat != null && compat.length > COMPATIBILITY_MAX_LENGTH) {
                    holder.registerProblem(
                        header, range,
                        SkillMdBundle.message("optional.error.compatibility.too.long", compat.length),
                        TruncateQuickFix(COMPATIBILITY_MAX_LENGTH),
                    )
                }

                // metadata: must be a Map if present
                val meta = parsed["metadata"]
                if (meta != null && meta !is Map<*, *>) {
                    holder.registerProblem(
                        header, range,
                        SkillMdBundle.message("optional.error.metadata.not.object", meta.javaClass.simpleName),
                    )
                }

                // allowed-tools: must be a string if present
                val allowedTools = parsed["allowed-tools"]
                if (allowedTools != null && allowedTools !is String) {
                    holder.registerProblem(
                        header, range,
                        SkillMdBundle.message("optional.error.allowed.tools.not.string", allowedTools.javaClass.simpleName),
                    )
                }
            }
        }
    }
}
