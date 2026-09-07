/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

@file:Suppress("UnstableApiUsage")

package dev.ghostflyby.skills

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.model.Pointer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.QueryExecutorBase
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.application.writeAction
import com.intellij.openapi.application.writeIntentReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.SearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.refactoring.rename.RenameProcessor
import com.intellij.refactoring.rename.api.ModifiableRenameUsage
import com.intellij.refactoring.rename.api.RenameTarget
import com.intellij.refactoring.rename.api.RenameUsage
import com.intellij.refactoring.rename.api.RenameUsageSearchParameters
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.editorFixture
import com.intellij.testFramework.junit5.fixture.extensionPointFixture
import com.intellij.testFramework.junit5.fixture.moduleFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.psiFileFixture
import com.intellij.testFramework.junit5.fixture.sourceRootFixture
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import com.intellij.util.Processor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownFile
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNotNull

@TestApplication
internal class SkillNameRenameTest {

    private companion object {
        const val NAME = "intellij-psi-vfs-safety"
    }

    private val extraDirectoryReferences = mutableListOf<ExtraSkillDirectoryReference>()
    private val projectFixture = projectFixture(openAfterCreation = true)
    private val project by projectFixture
    private val sourceRootFixture = projectFixture.moduleFixture(name = "agent-skills-test")
        .sourceRootFixture(
            pathFixture = tempPathFixture(subdirName = NAME),
        )
    private val sourceRoot by sourceRootFixture
    private val fileFixture = sourceRootFixture.psiFileFixture(
        SKILL_MD_FILE_NAME,
        skillFileContent("<caret>$NAME"),
    )
    private val file: MarkdownFile
        get() = fileFixture.get() as MarkdownFile
    private val editorFixture = fileFixture.editorFixture()
    private val editor by editorFixture
    private val extraDirectoryReferenceFixture = extensionPointFixture(ReferencesSearch.EP_NAME) {
        object : QueryExecutorBase<PsiReference, ReferencesSearch.SearchParameters>(true) {
            override fun processQuery(
                queryParameters: ReferencesSearch.SearchParameters,
                consumer: Processor<in PsiReference>,
            ) {
                extraDirectoryReferences
                    .filter { queryParameters.elementToSearch == it.directory }
                    .forEach { consumer.process(it) }
            }
        }
    }

    @BeforeEach
    fun waitForIndexes() {
        editor
        extraDirectoryReferenceFixture.get()
        sourceRoot.virtualFile.refresh(false, true)
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    @Test
    suspend fun `skill name scalar exposes symbol at value`() {
        val file = configureSkill(NAME)

        readAction {
            val scalar = requireSkillNameScalar(file)
            val offsetInElement = scalar.valueTextRangeInElement().startOffset
            val declarations = SkillNameDeclarationProvider().getDeclarations(scalar, offsetInElement)
            val symbols = declarations.map { it.symbol }.filterIsInstance<AgentSkillSymbol>()

            assertEquals(1, symbols.size)
            assertEquals(requireDirectory(NAME).virtualFile, symbols.single().virtualFile)
        }
    }

    @Test
    suspend fun `skill name scalar resolves at end of value`() {
        val file = configureSkill(NAME)
        val name = NAME
        val lastValueOffset = editor.document.text.indexOf(name) + name.lastIndex

        readAction {
            assertEquals(name, file.skillNameScalarAt(lastValueOffset)?.textValue)
        }
    }

    @Test
    suspend fun `symbol rename usage search includes directory reference and file rename`() {
        val file = configureSkill(NAME)
        val directory = requireDirectory(NAME)
        val usages = readAction { collectSymbolRenameUsages(directory) }

        assertEquals(2, usages.size)
        assertTrue(usages.any { it is SkillNameOccurrenceRenameUsage })
        assertTrue(usages.any { it is ModifiableRenameUsage && it.modifiesDirectoryNameTo("new-skill-name") })

        val nameUsage = usages.filterIsInstance<SkillNameOccurrenceRenameUsage>().single()
        nameUsage.updateModelTo("new-skill-name")
        readAction {
            assertEquals("new-skill-name", requireSkillNameScalar(file).textValue)
        }
    }

    @Test
    suspend fun `symbol rename usage search works from background without explicit read action`() {
        configureSkill(NAME)
        val directory = requireDirectory(NAME)
        val app = ApplicationManager.getApplication()

        val pointers = app.executeOnPooledThread<List<Pointer<out RenameUsage>>> {
            assertFalse(app.isReadAccessAllowed)
            collectSymbolRenameUsagePointers(directory)
        }.get()

        assertEquals(2, pointers.size)
        readAction {
            assertTrue(pointers.all { it.dereference() != null })
            assertTrue(pointers.any { it.dereference() is SkillNameOccurrenceRenameUsage })
        }
    }

    @Test
    suspend fun `symbol rename usage search collects references search pipeline`() {
        val primaryFile = configureSkill(NAME)
        val directory = requireDirectory(NAME)
        val aliasFile = configureSkillWithName("alias-skill", NAME)
        val aliasReference = readAction {
            ExtraSkillDirectoryReference(requireSkillNameScalar(aliasFile), directory)
        }
        registerExtraDirectoryReference(aliasReference)

        val references = readAction { ReferencesSearch.search(directory).findAll() }
        assertEquals(2, references.size)
        assertTrue(readAction { references.any { it.element == requireSkillNameScalar(primaryFile) } })
        assertTrue(references.any { it === aliasReference })

        val usages = readAction { collectSymbolRenameUsages(directory) }
        val nameOccurrences = usages.filterIsInstance<SkillNameOccurrenceRenameUsage>()

        assertEquals(2, nameOccurrences.size)
        assertTrue(usages.any { it is ModifiableRenameUsage && it.modifiesDirectoryNameTo("new-skill-name") })
        readAction {
            for (reference in references) {
                assertTrue(nameOccurrences.any { it.matchesReference(reference) })
            }
        }
    }

    @Test
    suspend fun `symbol rename usage uses directory reference value range`() {
        val file = configureSkillWithName("quoted-skill", "'quoted-skill'")
        val directory = requireDirectory("quoted-skill")

        val nameUsage = readAction {
            collectSymbolRenameUsages(directory)
                .filterIsInstance<SkillNameOccurrenceRenameUsage>()
                .single()
        }

        assertEquals(
            "quoted-skill",
            readAction { nameUsage.file.text.substring(nameUsage.range.startOffset, nameUsage.range.endOffset) },
        )
        nameUsage.updateModelTo("renamed-quoted-skill")
        readAction {
            assertTrue(file.text.contains("name: 'renamed-quoted-skill'"))
        }
    }

    @Test
    suspend fun `skill name occurrence pointer restores value range`() {
        configureSkillWithName("quoted-skill", "'quoted-skill'")
        val directory = requireDirectory("quoted-skill")
        val pointer = readAction {
            collectSymbolRenameUsagePointers(directory)
                .mapNotNull { it.dereference() as? SkillNameOccurrenceRenameUsage }
                .single()
        }

        readAction {
            assertEquals("quoted-skill", pointer.file.text.substring(pointer.range.startOffset, pointer.range.endOffset))
        }
    }

    @Test
    suspend fun `skill name occurrence pointer restores injected reference search occurrence`() {
        configureSkill(NAME)
        val directory = requireDirectory(NAME)
        val aliasFile = configureSkillWithName("alias-skill", NAME)
        val aliasReference = readAction {
            ExtraSkillDirectoryReference(requireSkillNameScalar(aliasFile), directory)
        }
        registerExtraDirectoryReference(aliasReference)

        val restoredOccurrences = readAction {
            collectSymbolRenameUsagePointers(directory)
                .mapNotNull { it.dereference() as? SkillNameOccurrenceRenameUsage }
        }

        readAction {
            assertTrue(restoredOccurrences.any { it.matchesReference(aliasReference) })
        }
    }

    @Test
    suspend fun `skill name occurrence model update commits documents before undo style update`() {
        val file = configureSkillWithName("quoted-skill", "'quoted-skill'")
        val directory = requireDirectory("quoted-skill")
        val usage = readAction {
            collectSymbolRenameUsages(directory)
                .filterIsInstance<SkillNameOccurrenceRenameUsage>()
                .single()
        }
        val update = usage.prepareSingleModelUpdate()

        WriteCommandAction.runWriteCommandAction(project) {
            update.updateModel("renamed-quoted-skill")
        }
        WriteCommandAction.runWriteCommandAction(project) {
            update.updateModel("quoted-skill")
        }

        readAction {
            assertTrue(file.text.contains("name: 'quoted-skill'"))
        }
    }

    @Test
    suspend fun `skill name occurrence model update unblocks document after update`() {
        val file = configureSkillWithName("quoted-skill", "'quoted-skill'")
        val directory = requireDirectory("quoted-skill")
        val usage = readAction {
            collectSymbolRenameUsages(directory)
                .filterIsInstance<SkillNameOccurrenceRenameUsage>()
                .single()
        }
        val update = usage.prepareSingleModelUpdate()
        val document = readAction {
            PsiDocumentManager.getInstance(project).getDocument(file) ?: error("Expected document")
        }

        WriteCommandAction.runWriteCommandAction(project) {
            update.updateModel("renamed-quoted-skill")
        }
        WriteCommandAction.runWriteCommandAction(project) {
            document.insertString(document.textLength, "\n")
        }

        readAction {
            assertTrue(file.text.contains("name: 'renamed-quoted-skill'"))
        }
    }

    @Test
    suspend fun `directory rename updates skill frontmatter name`() {
        configureSkill("intellij-junit5-platform-testing")
        val directory = requireDirectory("intellij-junit5-platform-testing")

        @Suppress("UnstableApiUsage") writeIntentReadAction {
            RenameProcessor(project, directory, "renamed-junit-skill", false, false).run()
        }

        val renamedDirectory = requireDirectory("renamed-junit-skill")
        readAction {
            assertEquals("renamed-junit-skill", requireSkillFile(renamedDirectory).skillNameScalar()?.textValue)
        }
    }

    @Test
    suspend fun `directory references search finds skill frontmatter name`() {
        val file = configureSkill(NAME)
        val directory = requireDirectory(NAME)

        val references = readAction { ReferencesSearch.search(directory).findAll() }

        assertEquals(1, references.size)
        readAction {
            assertSame(directory, references.single().resolve())
            assertSame(requireSkillNameScalar(file), references.single().element)
        }
        assertEquals(NAME, references.single().canonicalText)
    }

    @Test
    suspend fun `rename helper updates directory references`() {
        configureSkill(NAME)
        val directory = requireDirectory(NAME)

        @Suppress("UnstableApiUsage") writeIntentReadAction {
            renameSkillDirectory(project, directory, "helper-renamed-skill")
        }

        val renamedDirectory = requireDirectory("helper-renamed-skill")
        readAction {
            assertEquals("helper-renamed-skill", requireSkillFile(renamedDirectory).skillNameScalar()?.textValue)
        }
    }

    @Test
    suspend fun `rename helper ignores invalid skill names`() {
        configureSkill(NAME)
        val directory = requireDirectory(NAME)

        @Suppress("UnstableApiUsage") writeIntentReadAction {
            renameSkillDirectory(project, directory, "Invalid Skill Name")
        }

        assertSame(directory, requireDirectory(NAME))
        assertNull(findDirectoryInTempDir("Invalid Skill Name"))
        readAction {
            assertEquals(NAME, requireSkillFile(directory).skillNameScalar()?.textValue)
        }
    }

    @Test
    suspend fun `rename helper defers refactoring outside write action`() {
        configureSkill(NAME)
        val directory = requireDirectory(NAME)

        writeAction {
            renameSkillDirectory(project, directory, "deferred-renamed-skill")
        }

        assertNull(findDirectoryInTempDir("deferred-renamed-skill"))

        withContext(Dispatchers.EDT) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        }

        val renamedDirectory = requireDirectory("deferred-renamed-skill")
        readAction {
            assertEquals("deferred-renamed-skill", requireSkillFile(renamedDirectory).skillNameScalar()?.textValue)
        }
    }

    @Test
    suspend fun `auto rename directory quick fix updates skill frontmatter name`() {
        val file = configureSkillWithName("directory-name", "metadata-name")

        launchQuickFix(file, SkillMdBundle.message("quickfix.auto.rename.dir", "metadata-name"))

        val renamedDirectory = requireDirectory("metadata-name")
        readAction {
            assertEquals("metadata-name", requireSkillFile(renamedDirectory).skillNameScalar()?.textValue)
        }
    }

    @Test
    suspend fun `auto rename both quick fix updates through directory references`() {
        val file = configureSkillWithName("IntelliJ PSI VFS Safety", "IntelliJ PSI VFS Safety")

        launchQuickFix(file, SkillMdBundle.message("quickfix.auto.rename.both", NAME))

        val renamedDirectory = requireDirectory(NAME)
        readAction {
            assertEquals(NAME, requireSkillFile(renamedDirectory).skillNameScalar()?.textValue)
        }
    }

    @Test
    suspend fun `goto declaration from skill name returns parent directory`() {
        val file = configureSkill(NAME)
        moveCaretToName()

        readAction {
            val offset = editor.caretModel.offset
            val source = file.findElementAt(offset)
            val targets = SkillNameGotoDeclarationHandler().getGotoDeclarationTargets(source, offset, editor)

            assertNotNull(targets)
            assertEquals(requireDirectory(NAME).virtualFile, (targets.single() as PsiDirectory).virtualFile)
        }
    }

    private suspend fun configureSkill(name: String): MarkdownFile {
        if (name == NAME) return file
        return configureSkillWithName(name, name)
    }

    private suspend fun configureSkillWithName(directoryName: String, skillName: String): MarkdownFile {
        val virtualFile = writeAction {
            val skillDirectory = sourceRoot.virtualFile.findChild(directoryName)
                ?: sourceRoot.virtualFile.createChildDirectory(this@SkillNameRenameTest, directoryName)
            val skillFile = skillDirectory.findChild(SKILL_MD_FILE_NAME)
                ?: skillDirectory.createChildData(this@SkillNameRenameTest, SKILL_MD_FILE_NAME)
            VfsUtil.saveText(skillFile, skillFileContent(skillName))
            skillFile
        }
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        return readAction {
            PsiManager.getInstance(project).findFile(virtualFile) as? MarkdownFile
                ?: error("Expected Markdown file $virtualFile")
        }
    }

    private suspend fun launchQuickFix(file: MarkdownFile, text: String) {
        val descriptor = readAction {
            val problems = inspectionProblems(file)
            problems.firstOrNull { problem ->
                problem.fixes
                    ?.filterIsInstance<LocalQuickFix>()
                    ?.any { it.name == text } == true
            } ?: error(
                "Expected quick fix '$text', available: " +
                        problems.flatMap { it.fixes?.filterIsInstance<LocalQuickFix>()?.map { fix -> fix.name }.orEmpty() },
            )
        }
        val fix = descriptor.fixes
            ?.filterIsInstance<LocalQuickFix>()
            ?.singleOrNull { it.name == text }
            ?: error("Expected quick fix '$text'")
        if (fix.startInWriteAction()) {
            writeAction {
                fix.applyFix(project, descriptor)
            }
        } else {
            @Suppress("UnstableApiUsage") writeIntentReadAction {
                fix.applyFix(project, descriptor)
            }
        }
    }

    private fun inspectionProblems(file: MarkdownFile): List<ProblemDescriptor> {
        val yamlFile = file.skillNameScalar()?.containingFile as? YAMLFile
            ?: error("Expected injected YAML file in ${file.name}")
        val holder = ProblemsHolder(InspectionManager.getInstance(project), yamlFile, true)
        yamlFile.accept(SkillNameInspection().buildVisitor(holder, true))
        return holder.results
    }

    private suspend fun moveCaretToName() {
        val fragment = "psi-vfs"
        val offset = editor.document.text.indexOf(fragment)
        assertTrue(offset >= 0, "Expected fragment '$fragment' in editor text")
        withContext(Dispatchers.EDT) {
            editor.caretModel.moveToOffset(offset)
        }
    }

    private fun requireSkillNameScalar(file: PsiFile): YAMLScalar =
        file.skillNameScalar() ?: error("Expected top-level skill name scalar")

    private fun requireDirectory(name: String): PsiDirectory =
        findDirectoryInTempDir(name) ?: error("Expected fixture directory $name")

    private fun findDirectoryInTempDir(name: String): PsiDirectory? = runReadActionBlocking {
        if (sourceRoot.name == name) sourceRoot else sourceRoot.findSubdirectory(name)
    }

    private fun requireSkillFile(directory: PsiDirectory): MarkdownFile =
        directory.findFile(SKILL_MD_FILE_NAME) as? MarkdownFile
            ?: error("Expected $SKILL_MD_FILE_NAME in ${directory.name}")

    private fun collectSymbolRenameUsages(directory: PsiDirectory): List<RenameUsage> {
        return collectSymbolRenameUsageQuery(directory).findAll().toList()
    }

    private fun collectSymbolRenameUsagePointers(directory: PsiDirectory) =
        collectSymbolRenameUsageQuery(directory)
            .mapping { usage ->
                ApplicationManager.getApplication().assertReadAccessAllowed()
                usage.createPointer()
            }
            .findAll()
            .toList()

    private fun collectSymbolRenameUsageQuery(directory: PsiDirectory): com.intellij.util.Query<out RenameUsage> {
        val testProject = project
        val parameters = object : RenameUsageSearchParameters {
            override fun areValid(): Boolean = true
            override fun getProject(): Project = testProject
            override val target: RenameTarget = runReadActionBlocking {
                SkillSymbolRenameTarget(directory) ?: error("Expected skill rename target")
            }
            override val searchScope: SearchScope = GlobalSearchScope.projectScope(testProject)
        }
        return SkillNameRenameUsageSearcher().collectSearchRequest(parameters)
            ?: error("Expected skill rename usage query")
    }

    private fun registerExtraDirectoryReference(reference: ExtraSkillDirectoryReference) {
        extraDirectoryReferences += reference
    }

    private fun ModifiableRenameUsage.modifiesDirectoryNameTo(newName: String): Boolean {
        val updater = fileUpdater ?: return false
        return updater.prepareFileUpdate(this, newName).any { operation ->
            operation.javaClass.simpleName == "Rename"
        }
    }

    private fun SkillNameOccurrenceRenameUsage.updateModelTo(newName: String) {
        val update = prepareSingleModelUpdate()
        WriteCommandAction.runWriteCommandAction(project) {
            update.updateModel(newName)
        }
    }

    private fun SkillNameOccurrenceRenameUsage.prepareSingleModelUpdate() = runReadActionBlocking {
        modelUpdater.prepareModelUpdateBatch(listOf(this@prepareSingleModelUpdate)).singleOrNull()
    } ?: error("Expected name occurrence model update")

    private fun SkillNameOccurrenceRenameUsage.matchesReference(reference: PsiReference): Boolean =
        file == reference.element.containingFile &&
                range == reference.rangeInElement.shiftRight(reference.element.textRange.startOffset)

    private class ExtraSkillDirectoryReference(
        element: YAMLScalar,
        val directory: PsiDirectory,
    ) : PsiReferenceBase<YAMLScalar>(element, ElementManipulators.getValueTextRange(element), false) {
        override fun resolve(): PsiElement = directory
    }
}

private fun skillFileContent(skillName: String): String =
    """
    ---
    name: $skillName
    description: Test skill
    ---

    # Test Skill
    """.trimIndent()
