package dev.ghostflyby.livetemplateswithselection
import com.intellij.codeInsight.template.Template
import com.intellij.codeInsight.template.TemplateSubstitutor
import com.intellij.codeInsight.template.impl.TemplateImpl
import com.intellij.codeInsight.template.impl.TemplateSubstitutionContext


internal class ExpandSelectionTemplateSubstitutor : TemplateSubstitutor {
    override fun substituteTemplate(
        substitutionContext: TemplateSubstitutionContext,
        template: TemplateImpl,
    ): TemplateImpl? {
        val previous = substitutionContext.document.replacedSelection
        substitutionContext.document.replacedSelection = null


        if (previous.isNullOrEmpty() ||
            @Suppress("UnstableApiUsage")
            !template.isSelectionTemplate)
            return null
        return template.apply {
            @Suppress("UnstableApiUsage")
            string = template.string.replace(SELECTION, previous.replace("$", "$$"))
        }
    }

    companion object {
        private const val SELECTION = "$${Template.SELECTION}$"
    }
}
