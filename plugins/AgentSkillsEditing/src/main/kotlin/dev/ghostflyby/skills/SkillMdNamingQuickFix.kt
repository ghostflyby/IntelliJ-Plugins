package dev.ghostflyby.skills

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile

internal class FixSkillNameQuickFix(private val replacement: String) : LocalQuickFix {
    override fun getName(): String = SkillMdBundle.message("skill.md.quickfix.fix.name")
    override fun getFamilyName(): String = getName()
    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        changeNameField(project, descriptor, replacement)
    }
}

internal class MatchDirectoryNameQuickFix(private val directory: String) : LocalQuickFix {
    override fun getName(): String = SkillMdBundle.message("skill.md.quickfix.match.directory")
    override fun getFamilyName(): String = getName()
    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        changeNameField(project, descriptor, directory)
    }
}

private fun changeNameField(project: Project, descriptor: ProblemDescriptor, newValue: String) {
    val psiFile: PsiFile = descriptor.psiElement.containingFile ?: return
    val document = psiFile.viewProvider.document ?: return
    val text = psiFile.text
    val regex = Regex("""^name\s*:\s*.*$""", RegexOption.MULTILINE)
    val matchResult = regex.find(text)
    if (matchResult != null) {
        val newText = text.replaceRange(matchResult.range, "name: $newValue")
        WriteCommandAction.runWriteCommandAction(project) {
            document.setText(newText)
        }
    }
}
