package dev.ghostflyby.skills

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile

internal class SkillNameInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        return object : PsiElementVisitor() {
            override fun visitFile(file: PsiFile) {
                val result = SkillFrontmatterAnalyzer.analyze(file)
                val header = result.frontMatterHeader ?: return
                val range = result.range ?: return
                val parsed = result.parsed ?: return

                val rawName = parsed["name"] as? String

                // Missing
                if (rawName == null) {
                    holder.registerProblem(
                        header, SkillMdBundle.message("name.error.missing"),
                        ProblemHighlightType.GENERIC_ERROR_OR_WARNING, range,
                    )
                    return
                }

                // Empty
                if (rawName.isEmpty()) {
                    holder.registerProblem(
                        header, SkillMdBundle.message("name.error.empty"),
                        ProblemHighlightType.WARNING, range,
                        FixSkillNameQuickFix("my-new-skill"),
                    )
                    return
                }

                // Too long
                if (rawName.length > NAME_MAX_LENGTH) {
                    holder.registerProblem(
                        header, SkillMdBundle.message("name.error.too.long", rawName.length),
                        ProblemHighlightType.WARNING, range,
                        FixSkillNameQuickFix(rawName.take(NAME_MAX_LENGTH)),
                    )
                }

                // Starts with hyphen
                if (rawName.startsWith("-")) {
                    holder.registerProblem(
                        header, SkillMdBundle.message("name.error.start.hyphen", rawName),
                        ProblemHighlightType.WARNING, range,
                        FixSkillNameQuickFix(rawName.trimStart('-')),
                    )
                }

                // Ends with hyphen
                if (rawName.endsWith("-")) {
                    holder.registerProblem(
                        header, SkillMdBundle.message("name.error.end.hyphen", rawName),
                        ProblemHighlightType.WARNING, range,
                        FixSkillNameQuickFix(rawName.trimEnd('-')),
                    )
                }

                // Invalid characters
                if (!NAME_REGEX.matches(rawName)) {
                    val fixed = rawName
                        .lowercase()
                        .replace(Regex("[^a-z0-9-]+"), "-")
                        .replace(Regex("-{2,}"), "-")
                        .trim('-')
                    holder.registerProblem(
                        header, SkillMdBundle.message("name.error.invalid.chars", rawName),
                        ProblemHighlightType.WARNING, range,
                        FixSkillNameQuickFix(fixed),
                    )
                }

                // Consecutive hyphens
                if ("--" in rawName) {
                    val fixed = rawName.replace(Regex("-{2,}"), "-")
                    holder.registerProblem(
                        header, SkillMdBundle.message("name.error.consecutive.hyphens", rawName),
                        ProblemHighlightType.WARNING, range,
                        FixSkillNameQuickFix(fixed),
                    )
                }

                // Parent directory match
                val parentDir = result.virtualFile?.parent?.name
                if (parentDir != null && rawName != parentDir) {
                    holder.registerProblem(
                        header, SkillMdBundle.message("name.error.mismatch", rawName, parentDir),
                        ProblemHighlightType.WARNING, range,
                        MatchDirectoryNameQuickFix(parentDir),
                    )
                }
            }
        }
    }
}
