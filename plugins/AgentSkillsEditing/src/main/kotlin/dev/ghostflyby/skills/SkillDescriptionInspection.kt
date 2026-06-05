package dev.ghostflyby.skills

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile

internal class SkillDescriptionInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        return object : PsiElementVisitor() {
            override fun visitFile(file: PsiFile) {
                val result = SkillFrontmatterAnalyzer.analyze(file)
                val header = result.frontMatterHeader ?: return
                val range = result.range ?: return
                val parsed = result.parsed ?: return

                val rawDesc = parsed["description"] as? String

                if (rawDesc == null) {
                    holder.registerProblem(
                        header, range,
                        SkillMdBundle.message("description.error.missing"),
                    )
                    return
                }

                if (rawDesc.isEmpty()) {
                    holder.registerProblem(
                        header, range,
                        SkillMdBundle.message("description.error.empty"),
                    )
                    return
                }

                if (rawDesc.length > DESCRIPTION_MAX_LENGTH) {
                    holder.registerProblem(
                        header, range,
                        SkillMdBundle.message("description.error.too.long", rawDesc.length),
                        TruncateQuickFix(DESCRIPTION_MAX_LENGTH),
                    )
                }
            }
        }
    }
}
