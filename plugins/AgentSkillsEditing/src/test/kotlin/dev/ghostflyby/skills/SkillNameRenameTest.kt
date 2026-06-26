/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

@file:Suppress("UnstableApiUsage")

package dev.ghostflyby.skills

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.QueryExecutorBase
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.SearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.refactoring.rename.RenameProcessor
import com.intellij.refactoring.rename.api.ModifiableRenameUsage
import com.intellij.refactoring.rename.api.RenameTarget
import com.intellij.refactoring.rename.api.RenameUsage
import com.intellij.refactoring.rename.api.RenameUsageSearchParameters
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.Processor
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownFile
import org.jetbrains.yaml.psi.YAMLScalar
import java.nio.file.Path

internal class SkillNameRenameTest : BasePlatformTestCase() {

    override fun getTestDataPath(): String =
        Path.of("src/test/resources").toAbsolutePath().toString()

    @Suppress("UnstableApiUsage")
    fun `test skill name scalar exposes symbol at value`() {
        val file = configureSkill("intellij-psi-vfs-safety")
        val scalar = requireSkillNameScalar(file)
        val offsetInElement = scalar.valueTextRangeInElement().startOffset

        val declarations = SkillNameDeclarationProvider().getDeclarations(scalar, offsetInElement)
        val symbols = declarations.map { it.symbol }.filterIsInstance<AgentSkillSymbol>()

        assertSize(1, symbols)
        assertEquals(requireDirectory("intellij-psi-vfs-safety").virtualFile, symbols.single().virtualFile)
    }

    fun `test skill name scalar resolves at end of value and eol`() {
        val file = configureSkill("intellij-psi-vfs-safety")
        val name = "intellij-psi-vfs-safety"
        val endOffset = myFixture.editor.document.text.indexOf(name) + name.length

        assertEquals(name, file.skillNameScalarAt(endOffset)?.textValue)
        assertEquals(name, file.skillNameScalarAt(endOffset + 1)?.textValue)
    }

    fun `test symbol rename usage search includes directory reference and file rename`() {
        val file = configureSkill("intellij-psi-vfs-safety")
        val directory = requireDirectory("intellij-psi-vfs-safety")
        val usages = collectSymbolRenameUsages(directory)

        assertEquals(2, usages.size)
        assertTrue(usages.any { it is SkillNameOccurrenceRenameUsage })
        assertTrue(usages.any { it is ModifiableRenameUsage && it.modifiesDirectoryNameTo("new-skill-name") })

        val nameUsage = usages.filterIsInstance<SkillNameOccurrenceRenameUsage>().single()
        nameUsage.updateModelTo("new-skill-name")
        assertEquals("new-skill-name", requireSkillNameScalar(file).textValue)
    }

    fun `test symbol rename usage search collects references search pipeline`() {
        val primaryFile = configureSkill("intellij-psi-vfs-safety")
        val directory = requireDirectory("intellij-psi-vfs-safety")
        val aliasFile = configureSkillWithName("alias-skill", "intellij-psi-vfs-safety")
        val aliasReference = ExtraSkillDirectoryReference(requireSkillNameScalar(aliasFile), directory)
        registerExtraDirectoryReference(aliasReference)

        val references = ReferencesSearch.search(directory).findAll()
        assertSize(2, references)
        assertTrue(references.any { it.element == requireSkillNameScalar(primaryFile) })
        assertTrue(references.any { it === aliasReference })

        val usages = collectSymbolRenameUsages(directory)
        val nameOccurrences = usages.filterIsInstance<SkillNameOccurrenceRenameUsage>()

        assertSize(2, nameOccurrences)
        assertTrue(usages.any { it is ModifiableRenameUsage && it.modifiesDirectoryNameTo("new-skill-name") })
        for (reference in references) {
            assertTrue(nameOccurrences.any { it.matchesReference(reference) })
        }
    }

    fun `test symbol rename usage uses directory reference value range`() {
        val file = configureSkillWithName("quoted-skill", "'quoted-skill'")
        val directory = requireDirectory("quoted-skill")

        val nameUsage = collectSymbolRenameUsages(directory)
            .filterIsInstance<SkillNameOccurrenceRenameUsage>()
            .single()

        assertEquals(
            "quoted-skill",
            nameUsage.file.text.substring(nameUsage.range.startOffset, nameUsage.range.endOffset),
        )
        nameUsage.updateModelTo("renamed-quoted-skill")
        assertTrue(file.text.contains("name: 'renamed-quoted-skill'"))
    }

    fun `test directory rename updates skill frontmatter name`() {
        configureSkill("intellij-junit5-platform-testing")
        val directory = requireDirectory("intellij-junit5-platform-testing")

        RenameProcessor(project, directory, "renamed-junit-skill", false, false).run()

        val renamedDirectory = findDirectoryInTempDir("renamed-junit-skill")
        assertNotNull(renamedDirectory)
        assertEquals("renamed-junit-skill", requireSkillFile(renamedDirectory!!).skillNameScalar()?.textValue)
    }

    fun `test directory references search finds skill frontmatter name`() {
        val file = configureSkill("intellij-psi-vfs-safety")
        val directory = requireDirectory("intellij-psi-vfs-safety")

        val references = ReferencesSearch.search(directory).findAll()

        assertEquals(1, references.size)
        assertSame(directory, references.single().resolve())
        assertSame(requireSkillNameScalar(file), references.single().element)
        assertEquals("intellij-psi-vfs-safety", references.single().canonicalText)
    }

    fun `test rename helper updates directory references`() {
        configureSkill("intellij-psi-vfs-safety")
        val directory = requireDirectory("intellij-psi-vfs-safety")

        renameSkillDirectory(project, directory, "helper-renamed-skill")

        val renamedDirectory = findDirectoryInTempDir("helper-renamed-skill")
        assertNotNull(renamedDirectory)
        assertEquals("helper-renamed-skill", requireSkillFile(renamedDirectory!!).skillNameScalar()?.textValue)
    }

    fun `test rename helper ignores invalid skill names`() {
        configureSkill("intellij-psi-vfs-safety")
        val directory = requireDirectory("intellij-psi-vfs-safety")

        renameSkillDirectory(project, directory, "Invalid Skill Name")

        assertSame(directory, requireDirectory("intellij-psi-vfs-safety"))
        assertNull(findDirectoryInTempDir("Invalid Skill Name"))
        assertEquals("intellij-psi-vfs-safety", requireSkillFile(directory).skillNameScalar()?.textValue)
    }

    fun `test rename helper defers refactoring outside write action`() {
        configureSkill("intellij-psi-vfs-safety")
        val directory = requireDirectory("intellij-psi-vfs-safety")

        ApplicationManager.getApplication().runWriteAction {
            renameSkillDirectory(project, directory, "deferred-renamed-skill")
        }

        assertNull(findDirectoryInTempDir("deferred-renamed-skill"))

        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        val renamedDirectory = findDirectoryInTempDir("deferred-renamed-skill")
        assertNotNull(renamedDirectory)
        assertEquals("deferred-renamed-skill", requireSkillFile(renamedDirectory!!).skillNameScalar()?.textValue)
    }

    fun `test auto rename directory quick fix updates skill frontmatter name`() {
        configureSkillWithName("directory-name", "metadata-name")

        launchQuickFix(SkillMdBundle.message("quickfix.auto.rename.dir", "metadata-name"))

        val renamedDirectory = findDirectoryInTempDir("metadata-name")
        assertNotNull(renamedDirectory)
        assertEquals("metadata-name", requireSkillFile(renamedDirectory!!).skillNameScalar()?.textValue)
    }

    fun `test auto rename both quick fix updates through directory references`() {
        configureSkillWithName("IntelliJ PSI VFS Safety", "IntelliJ PSI VFS Safety")

        launchQuickFix(SkillMdBundle.message("quickfix.auto.rename.both", "intellij-psi-vfs-safety"))

        val renamedDirectory = findDirectoryInTempDir("intellij-psi-vfs-safety")
        assertNotNull(renamedDirectory)
        assertEquals("intellij-psi-vfs-safety", requireSkillFile(renamedDirectory!!).skillNameScalar()?.textValue)
    }

    fun `test goto declaration from skill name returns parent directory`() {
        val file = configureSkill("intellij-psi-vfs-safety")
        moveCaretToName()
        val source = file.findElementAt(myFixture.editor.caretModel.offset)
        val targets = SkillNameGotoDeclarationHandler()
            .getGotoDeclarationTargets(source, myFixture.editor.caretModel.offset, myFixture.editor)

        assertNotNull(targets)
        assertSame(requireDirectory("intellij-psi-vfs-safety"), targets!!.single())
    }

    private fun configureSkill(name: String): MarkdownFile {
        myFixture.copyDirectoryToProject("fixtures/mcp-sdk-skills/$name", name)
        return myFixture.configureFromTempProjectFile("$name/$SKILL_MD_FILE_NAME") as MarkdownFile
    }

    private fun configureSkillWithName(directoryName: String, skillName: String): MarkdownFile {
        myFixture.addFileToProject(
            "$directoryName/$SKILL_MD_FILE_NAME",
            """
            ---
            name: $skillName
            description: Test skill
            ---

            # Test Skill
            """.trimIndent(),
        )
        val file = myFixture.configureFromTempProjectFile("$directoryName/$SKILL_MD_FILE_NAME") as MarkdownFile
        val offset = myFixture.editor.document.text.indexOf(skillName)
        assertTrue("Expected skill name '$skillName' in editor text", offset >= 0)
        myFixture.editor.caretModel.moveToOffset(offset)
        return file
    }

    private fun launchQuickFix(text: String) {
        myFixture.enableInspections(SkillNameInspection::class.java)
        myFixture.doHighlighting()
        val action = myFixture.findSingleIntention(text)
        myFixture.launchAction(action)
    }

    private fun moveCaretToName() {
        val fragment = "psi-vfs"
        val offset = myFixture.editor.document.text.indexOf(fragment)
        assertTrue("Expected fragment '$fragment' in editor text", offset >= 0)
        myFixture.editor.caretModel.moveToOffset(offset)
    }

    private fun requireSkillNameScalar(file: PsiFile): YAMLScalar =
        file.skillNameScalar() ?: error("Expected top-level skill name scalar")

    private fun requireDirectory(name: String): PsiDirectory =
        findDirectoryInTempDir(name) ?: error("Expected fixture directory $name")

    private fun findDirectoryInTempDir(name: String): PsiDirectory? {
        val virtualFile = myFixture.tempDirFixture.getFile(name) ?: return null
        return PsiManager.getInstance(project).findDirectory(virtualFile)
    }

    private fun requireSkillFile(directory: PsiDirectory): MarkdownFile =
        directory.findFile(SKILL_MD_FILE_NAME) as? MarkdownFile
            ?: error("Expected $SKILL_MD_FILE_NAME in ${directory.name}")

    private fun collectSymbolRenameUsages(directory: PsiDirectory): List<RenameUsage> {
        val testProject = project
        val parameters = object : RenameUsageSearchParameters {
            override fun areValid(): Boolean = true
            override fun getProject(): Project = testProject
            override val target: RenameTarget = AgentSkillSymbol(directory)
            override val searchScope: SearchScope = GlobalSearchScope.projectScope(testProject)
        }
        val query = SkillNameRenameUsageSearcher().collectSearchRequest(parameters)
            ?: error("Expected skill rename usage query")
        return query.findAll().toList()
    }

    private fun registerExtraDirectoryReference(reference: ExtraSkillDirectoryReference) {
        val executor = object : QueryExecutorBase<PsiReference, ReferencesSearch.SearchParameters>() {
            override fun processQuery(
                queryParameters: ReferencesSearch.SearchParameters,
                consumer: Processor<in PsiReference>,
            ) {
                if (queryParameters.elementToSearch == reference.directory) {
                    consumer.process(reference)
                }
            }
        }
        ExtensionTestUtil.addExtensions(ReferencesSearch.EP_NAME, listOf(executor), testRootDisposable)
    }

    private fun ModifiableRenameUsage.modifiesDirectoryNameTo(newName: String): Boolean {
        val updater = fileUpdater ?: return false
        return updater.prepareFileUpdate(this, newName).any { operation ->
            operation.javaClass.simpleName == "Rename"
        }
    }

    private fun SkillNameOccurrenceRenameUsage.updateModelTo(newName: String) {
        val update = modelUpdater.prepareModelUpdate(this) ?: error("Expected name occurrence model update")
        WriteCommandAction.runWriteCommandAction(project) {
            update.updateModel(newName)
        }
    }

    private fun SkillNameOccurrenceRenameUsage.matchesReference(reference: PsiReference): Boolean =
        file == reference.element.containingFile &&
                range == reference.rangeInElement.shiftRight(reference.element.textRange.startOffset)

    private class ExtraSkillDirectoryReference(
        element: YAMLScalar,
        val directory: PsiDirectory,
    ) : PsiReferenceBase<YAMLScalar>(element, element.valueTextRangeInElement(), false) {
        override fun resolve(): PsiElement = directory
    }

}
