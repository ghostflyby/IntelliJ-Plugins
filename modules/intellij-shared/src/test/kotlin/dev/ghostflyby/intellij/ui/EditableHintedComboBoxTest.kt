/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.intellij.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.observable.properties.PropertyGraph
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.ui.components.fields.ExtendableTextComponent
import com.intellij.ui.dsl.builder.panel
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import javax.swing.SwingUtilities

@TestApplication
internal class EditableHintedComboBoxTest {
    @Test
    fun installsEditableEditorWithJbPopupStyle() {
        val comboBox = createComboBox()
        flushEdt()

        assertFalse(comboBox.isSwingPopup)
        assertTrue(comboBox.isEditable)
        assertSame(comboBox.editorTextField, comboBox.editor.editorComponent)
        assertSame(comboBox, comboBox.editorTextField.parent)
        assertTrue(comboBox.editorTextField.isEnabled)
        assertTrue(comboBox.editorTextField.isEditable)
    }

    @Test
    fun addNotifyKeepsHintedEditorInstalled() {
        val comboBox = createComboBox()

        comboBox.addNotify()
        flushEdt()

        assertTrue(comboBox.isEditable)
        assertSame(comboBox.editorTextField, comboBox.editor.editorComponent)
        assertSame(comboBox, comboBox.editorTextField.parent)
        assertTrue(comboBox.editorTextField.isEnabled)
        assertTrue(comboBox.editorTextField.isEditable)
    }

    @Test
    fun updateUiKeepsHintedEditorInstalled() {
        val comboBox = createComboBox()

        comboBox.updateUI()
        flushEdt()

        assertFalse(comboBox.isSwingPopup)
        assertTrue(comboBox.isEditable)
        assertSame(comboBox.editorTextField, comboBox.editor.editorComponent)
        assertSame(comboBox, comboBox.editorTextField.parent)
        assertTrue(comboBox.editorTextField.isEnabled)
        assertTrue(comboBox.editorTextField.isEditable)
    }

    @Test
    fun storesItemsInComboBoxModel() {
        val comboBox = createComboBox()

        comboBox.addItem(choice("project"))
        comboBox.addItem(choice("path"))

        assertEquals(2, comboBox.model.size)
        assertEquals("project", comboBox.model.getElementAt(0).id)
        assertEquals("path", comboBox.model.getElementAt(1).id)
    }

    @Test
    fun selectingDropdownItemUpdatesText() {
        val comboBox = createComboBox()
        val project = choice("project", text = "Project", hint = "/repo/mill")
        comboBox.addItem(project)

        comboBox.selectedItem = project

        assertEquals(project, comboBox.selectedItem)
        assertEquals("Project", comboBox.editorTextField.text)
    }

    @Test
    fun typingMatchingTextKeepsSelectedItemUntilCommit() {
        val comboBox = createComboBox()
        val path = choice("path", text = "PATH")
        val project = choice("project", text = "Project")
        comboBox.addItem(project)
        comboBox.addItem(path)
        comboBox.selectedItem = project

        comboBox.editorTextField.text = "PATH"

        assertEquals(project, comboBox.selectedItem)
        assertEquals("PATH", comboBox.editorTextField.text)
    }

    @Test
    fun typingMatchingTextDoesNotCommitSelectedItem() {
        val comboBox = createComboBox()
        val path = choice("path", text = "PATH")
        val project = choice("project", text = "Project")
        comboBox.addItem(project)
        comboBox.addItem(path)
        comboBox.selectedItem = project

        comboBox.editorTextField.text = "PATH"

        assertEquals(project, comboBox.selectedItem)
        assertEquals("PATH", comboBox.editorTextField.text)
    }

    @Test
    fun typingCustomTextDoesNotCreateSelectedItem() {
        val comboBox = createComboBox(createCustomValues = true)
        val project = choice("project", text = "Project")
        comboBox.addItem(project)
        comboBox.selectedItem = project

        comboBox.editorTextField.text = "/usr/local/bin/mill"

        assertEquals(project, comboBox.selectedItem)
        assertEquals("/usr/local/bin/mill", comboBox.editorTextField.text)
    }

    @Test
    fun unresolvedTextPreservesDraftAndSelectedItem() {
        val comboBox = createComboBox(createCustomValues = false)
        val project = choice("project", text = "Project")
        comboBox.addItem(project)
        comboBox.selectedItem = project

        comboBox.editorTextField.text = "unknown"

        assertEquals(project, comboBox.selectedItem)
        assertEquals("unknown", comboBox.editorTextField.text)
    }

    @Test
    fun typingUnresolvedTextDoesNotClearDraft() {
        val comboBox = createComboBox(createCustomValues = false)
        val project = choice("project", text = "Project")
        comboBox.addItem(project)
        comboBox.selectedItem = project

        comboBox.editorTextField.text = "unknown"

        assertEquals(project, comboBox.selectedItem)
        assertEquals("unknown", comboBox.editorTextField.text)
    }

    @Test
    fun bindTextSynchronizesEditorDraftToPropertyWithoutChangingSelectedItem() {
        val comboBox = createComboBox()
        val project = choice("project", text = "Project")
        comboBox.addItem(project)
        comboBox.selectedItem = project
        val property = PropertyGraph().property(comboBox.editorTextField.text.orEmpty())
        panel {
            row {
                cell(comboBox).bindText(property)
            }
        }

        comboBox.editorTextField.text = "PATH"

        assertEquals("PATH", property.get())
        assertEquals(project, comboBox.selectedItem)
    }

    @Test
    fun bindTextSynchronizesPropertyToEditorDraftWithoutChangingSelectedItem() {
        val comboBox = createComboBox()
        val project = choice("project", text = "Project")
        comboBox.addItem(project)
        comboBox.selectedItem = project
        val property = PropertyGraph().property(comboBox.editorTextField.text.orEmpty())
        panel {
            row {
                cell(comboBox).bindText(property)
            }
        }

        property.set("PATH")

        assertEquals("PATH", comboBox.editorTextField.text)
        assertEquals(project, comboBox.selectedItem)
    }

    @Test
    fun bindTextReceivesSelectedItemTextWriteBack() {
        val comboBox = createComboBox()
        val project = choice("project", text = "Project")
        val path = choice("path", text = "PATH")
        comboBox.addItem(project)
        comboBox.addItem(path)
        val property = PropertyGraph().property("")
        panel {
            row {
                cell(comboBox).bindText(property)
            }
        }

        comboBox.selectedItem = path

        assertEquals("PATH", comboBox.editorTextField.text)
        assertEquals("PATH", property.get())
    }

    @Test
    fun bindTextTracksVisibleEditorAfterUpdateUi() {
        val comboBox = createComboBox()
        val project = choice("project", text = "Project")
        comboBox.addItem(project)
        comboBox.selectedItem = project
        val property = PropertyGraph().property(comboBox.editorTextField.text.orEmpty())
        panel {
            row {
                cell(comboBox).bindText(property)
            }
        }
        val boundEditor = comboBox.editorTextField

        comboBox.updateUI()
        flushEdt()
        comboBox.editorTextField.text = "PATH"

        assertSame(boundEditor, comboBox.editorTextField)
        assertSame(comboBox.editorTextField, comboBox.editor.editorComponent)
        assertEquals("PATH", property.get())
        assertEquals(project, comboBox.selectedItem)
    }

    @Test
    fun boundHintsUpdateVisibleEditorAfterUpdateUi() {
        val comboBox = createComboBox()
        val propertyGraph = PropertyGraph()
        val leftHintProperty = propertyGraph.property("")
        val rightHintProperty = propertyGraph.property("")
        panel {
            row {
                cell(comboBox)
                    .bindLeftHint(leftHintProperty)
                    .bindRightHint(rightHintProperty)
            }
        }
        val boundEditor = comboBox.editorTextField

        comboBox.updateUI()
        flushEdt()
        leftHintProperty.set("/repo/mill")
        rightHintProperty.set("1.0.0")

        assertSame(boundEditor, comboBox.editorTextField)
        assertSame(comboBox.editorTextField, comboBox.editor.editorComponent)
        assertEquals("/repo/mill", comboBox.leftHint)
        assertEquals("1.0.0", comboBox.rightHint)
    }

    @Test
    fun boundHintsDoNotCommitDraftText() {
        val comboBox = createComboBox()
        val project = choice("project", text = "Project")
        comboBox.addItem(project)
        comboBox.selectedItem = project
        val propertyGraph = PropertyGraph()
        val leftHintProperty = propertyGraph.property("")
        val rightHintProperty = propertyGraph.property("")
        panel {
            row {
                cell(comboBox)
                    .bindLeftHint(leftHintProperty)
                    .bindRightHint(rightHintProperty)
            }
        }

        comboBox.editorTextField.text = "PATH"
        leftHintProperty.set("/repo/mill")
        rightHintProperty.set("1.0.0")

        assertEquals("/repo/mill", comboBox.leftHint)
        assertEquals("1.0.0", comboBox.rightHint)
        assertEquals(project, comboBox.selectedItem)
    }

    @Test
    fun rightHintAndTrailingExtensionReserveEditorSpace() {
        val comboBox = createComboBox()
        val field = comboBox.editorTextField
        val initialRightInset = field.margin.right

        comboBox.rightHint = "1.0.0"
        comboBox.editorTextField.putClientProperty("JComponent.outline", "error")
        comboBox.editorTextField.addExtension(
            ExtendableTextComponent.Extension.create(
                AllIcons.General.OpenDisk,
                AllIcons.General.OpenDiskHover,
                "Open",
                null,
            ),
        )
        comboBox.rightHint = "1.0.1"

        assertEquals("1.0.1", comboBox.rightHint)
        assertEquals("error", comboBox.editorTextField.getClientProperty("JComponent.outline"))
        assertNotEquals(initialRightInset, field.margin.right)
    }

    private fun createComboBox(createCustomValues: Boolean = true): EditableHintedComboBox<Choice> {
        return EditableHintedComboBox<Choice>(adapter = TestChoiceAdapter(createCustomValues))
    }

    private fun choice(
        id: String,
        text: String = id,
        hint: String? = null,
    ): Choice {
        return Choice(id = id, text = text, hint = hint)
    }

    private fun flushEdt() {
        SwingUtilities.invokeAndWait {}
    }

    private data class Choice(
        val id: String,
        val text: String,
        val hint: String?,
    )

    private class TestChoiceAdapter(
        private val createCustomValues: Boolean,
    ) : EditableHintedComboBoxAdapter<Choice> {
        override fun text(t: Choice): String {
            return t.text
        }

        override fun fromText(
            text: String,
        ): Choice? {
            return if (createCustomValues) {
                Choice(id = "custom:$text", text = text, hint = null)
            } else {
                null
            }
        }
    }
}
