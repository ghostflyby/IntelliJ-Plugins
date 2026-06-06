/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 *
 * This file is part of IntelliJ-Plugins by ghostflyby
 *
 * IntelliJ-Plugins by ghostflyby is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3.0 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, see
 * <https://www.gnu.org/licenses/>.
 */

package dev.ghostflyby.skills

import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.refactoring.rename.RenameHandlerRegistry
import com.intellij.refactoring.rename.RenameProcessor
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.intellij.plugins.markdown.lang.MarkdownFileType
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownFile
import org.jetbrains.yaml.psi.YAMLScalar
import java.nio.file.Path

internal class SkillNameRenameTest : BasePlatformTestCase() {

    override fun getTestDataPath(): String =
        Path.of("src/test/resources").toAbsolutePath().toString()

    fun `test skill name rename handler is only custom handler inside name`() {
        configureSkill("intellij-psi-vfs-safety")
        moveCaretToName()

        val handlers = RenameHandlerRegistry.getInstance().getRenameHandlers(renameDataContext())

        assertSize(1, handlers)
        assertInstanceOf(handlers.single(), SkillNameRenameHandler::class.java)
    }

    fun `test skill name scalar resolves at end of value and eol`() {
        val file = configureSkill("intellij-psi-vfs-safety")
        val name = "intellij-psi-vfs-safety"
        val endOffset = myFixture.editor.document.text.indexOf(name) + name.length

        assertEquals(name, file.skillNameScalarAt(endOffset)?.textValue)
        assertEquals(name, file.skillNameScalarAt(endOffset + 1)?.textValue)
    }

    fun `test scalar rename updates skill directory and frontmatter name`() {
        val file = configureSkill("intellij-psi-vfs-safety")
        val scalar = requireSkillNameScalar(file)

        RenameProcessor(project, scalar, "new-skill-name", false, false).run()

        val renamedDirectory = findDirectoryInTempDir("new-skill-name")
        assertNotNull(renamedDirectory)
        assertEquals("new-skill-name", requireSkillFile(renamedDirectory!!).skillNameScalar()?.textValue)
    }

    fun `test directory rename updates skill frontmatter name`() {
        configureSkill("intellij-junit5-platform-testing")
        val directory = requireDirectory("intellij-junit5-platform-testing")

        RenameProcessor(project, directory, "renamed-junit-skill", false, false).run()

        val renamedDirectory = findDirectoryInTempDir("renamed-junit-skill")
        assertNotNull(renamedDirectory)
        assertEquals("renamed-junit-skill", requireSkillFile(renamedDirectory!!).skillNameScalar()?.textValue)
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

    fun `test handler and navigation ignore non skill name contexts`() {
        val file = myFixture.configureByText(
            MarkdownFileType.INSTANCE,
            """
            ---
            description: <caret>plain text
            nested:
              name: nested-name
            ---
            # Not a skill name
            """.trimIndent(),
        )

        assertFalse(SkillNameRenameHandler().isAvailableOnDataContext(renameDataContext()))
        assertNull(file.skillNameScalarAt(myFixture.editor.caretModel.offset))

        val source = file.findElementAt(myFixture.editor.caretModel.offset)
        val targets = SkillNameGotoDeclarationHandler()
            .getGotoDeclarationTargets(source, myFixture.editor.caretModel.offset, myFixture.editor)
        assertNull(targets)
    }

    fun `test handler ignores description in real skill fixture`() {
        configureSkill("intellij-junit5-platform-testing")
        val descriptionOffset = myFixture.editor.document.text.indexOf("reviewing IntelliJ")
        myFixture.editor.caretModel.moveToOffset(descriptionOffset)

        assertFalse(SkillNameRenameHandler().isAvailableOnDataContext(renameDataContext()))
    }

    private fun configureSkill(name: String): MarkdownFile {
        myFixture.copyDirectoryToProject("fixtures/mcp-sdk-skills/$name", name)
        return myFixture.configureFromTempProjectFile("$name/$SKILL_MD_FILE_NAME") as MarkdownFile
    }

    private fun moveCaretToName() {
        val fragment = "psi-vfs"
        val offset = myFixture.editor.document.text.indexOf(fragment)
        assertTrue("Expected fragment '$fragment' in editor text", offset >= 0)
        myFixture.editor.caretModel.moveToOffset(offset)
    }

    private fun renameDataContext() = SimpleDataContext.builder()
        .add(CommonDataKeys.PROJECT, project)
        .add(CommonDataKeys.EDITOR, myFixture.editor)
        .add(CommonDataKeys.PSI_FILE, myFixture.file)
        .build()

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
}
