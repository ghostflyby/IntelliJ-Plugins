/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.skills

import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.writeIntentReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiDirectory
import com.intellij.refactoring.rename.RenameProcessor
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.*
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNotNull
import java.nio.file.Path.of

@TestApplication
internal class SkillNameRenameTest {

    companion object {
        const val NAME = "intellij-psi-vfs-safety"
        val projectFixture = projectFixture(openAfterCreation = true)
        val project by projectFixture

    }


    val moduleFixture = projectFixture.moduleFixture(name = "agent-skills-test")

    val sourceRootFixture = moduleFixture.sourceRootFixture(
        pathFixture = projectFixture.pathInProjectFixture(of(this.toString(), NAME)),
    )
    val sourceRoot by sourceRootFixture
    val fileFixture = sourceRootFixture.psiFileFixture(
        "SKILL.md",
            """
                ---
                description: plain text
                name: <caret>$NAME
                ---
                # a skill
                """.trimIndent(),
        )

    val file: MarkdownFile
        get() {
            return fileFixture.get() as MarkdownFile
        }
    val editorFixture = fileFixture.editorFixture()
    val editor by editorFixture

    @Test
    suspend fun `scalar rename updates skill directory and frontmatter name`() {
        @Suppress("UnstableApiUsage") writeIntentReadAction {
            val scalar = file.skillNameScalar()
            assertNotNull(scalar) { "Expected top-level skill name scalar in ${file.name}" }

            RenameProcessor(project, scalar, "new-skill-name", false, false).run()
            assertEquals("new-skill-name", sourceRoot.name)
            assertEquals(
                "new-skill-name",
                requireSkillFile(sourceRoot).skillNameScalar()?.textValue,
            )
        }
    }

    @Test
    suspend fun `directory rename updates skill frontmatter name`() {
        val newName = "renamed-junit-skill"
        @Suppress("UnstableApiUsage") writeIntentReadAction {
            RenameProcessor(project, sourceRoot, newName, false, false).run()
            assertEquals(newName, sourceRoot.name)
            assertEquals(
                newName,
                requireSkillFile(sourceRoot).skillNameScalar()?.textValue,
            )
        }
    }

    @Test
    suspend fun `goto declaration from skill name returns parent directory`() {

        readAction {
            val offset1 = editor.caretModel.offset
            val source1 = file.findElementAt(offset1)
            val targets1 = SkillNameGotoDeclarationHandler().getGotoDeclarationTargets(source1, offset1, editor)
            assertEquals(
                sourceRoot,
                requireNotNull(targets1).single(),
            )
        }
    }

    @Test
    suspend fun `handler ignores description in real skill fixture`() {
        readAction {
            assertFalse(
                SkillNameRenameHandler().isAvailableOnDataContext(
                    renameDataContext(
                        file,
                        editor,
                    ),
                ),
            )
        }
    }

    fun renameDataContext(file: MarkdownFile, editor: Editor) =
        SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).add(CommonDataKeys.EDITOR, editor)
            .add(CommonDataKeys.HOST_EDITOR, editor).add(CommonDataKeys.PSI_FILE, file)
        .build()

    fun requireSkillFile(directory: PsiDirectory): MarkdownFile =
        directory.findFile(SKILL_MD_FILE_NAME) as? MarkdownFile
            ?: error("Expected $SKILL_MD_FILE_NAME in ${directory.name}")

}
