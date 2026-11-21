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
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.SwingUtilities

@TestApplication
internal class EditableHintedComboBoxTest {
    @Test
    fun installsEditableEditorWithJbPopupStyle() {
        edt {
            val comboBox = createComboBox()

            assertFalse(comboBox.isSwingPopup)
            assertTrue(comboBox.isEditable)
            assertSame(comboBox.editorTextField, comboBox.editor.editorComponent)
            assertSame(comboBox, comboBox.editorTextField.parent)
            assertTrue(comboBox.editorTextField.isEnabled)
            assertTrue(comboBox.editorTextField.isEditable)
        }
    }

    @Test
    fun addNotifyKeepsHintedEditorInstalled() {
        edt {
            val comboBox = createComboBox()

            comboBox.addNotify()

            assertTrue(comboBox.isEditable)
            assertSame(comboBox.editorTextField, comboBox.editor.editorComponent)
            assertSame(comboBox, comboBox.editorTextField.parent)
            assertTrue(comboBox.editorTextField.isEnabled)
            assertTrue(comboBox.editorTextField.isEditable)
        }
    }

    @Test
    fun updateUiKeepsHintedEditorInstalled() {
        edt {
            val comboBox = createComboBox()

            comboBox.updateUI()

            assertFalse(comboBox.isSwingPopup)
            assertTrue(comboBox.isEditable)
            assertSame(comboBox.editorTextField, comboBox.editor.editorComponent)
            assertSame(comboBox, comboBox.editorTextField.parent)
            assertTrue(comboBox.editorTextField.isEnabled)
            assertTrue(comboBox.editorTextField.isEditable)
        }
    }

    @Test
    fun storesItemsInComboBoxModel() {
        edt {
            val comboBox = createComboBox()

            comboBox.addItem(choice("project"))
            comboBox.addItem(choice("path"))

            assertEquals(2, comboBox.model.size)
            assertEquals("project", comboBox.model.getElementAt(0).id)
            assertEquals("path", comboBox.model.getElementAt(1).id)
        }
    }

    @Test
    fun selectingDropdownItemUpdatesText() {
        edt {
            val comboBox = createComboBox()
            val project = choice("project", text = "Project", hint = "/repo/mill")
            comboBox.addItem(project)

            comboBox.selectedItem = project

            assertEquals(project, comboBox.selectedItem)
            assertEquals("Project", comboBox.editorTextField.text)
        }
    }

    @Test
    fun typingMatchingTextKeepsSelectedItemUntilCommit() {
        edt {
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
    }

    @Test
    fun typingCustomTextDoesNotCreateSelectedItem() {
        edt {
            val comboBox = createComboBox(createCustomValues = true)
            val project = choice("project", text = "Project")
            comboBox.addItem(project)
            comboBox.selectedItem = project

            comboBox.editorTextField.text = "/usr/local/bin/mill"

            assertEquals(project, comboBox.selectedItem)
            assertEquals("/usr/local/bin/mill", comboBox.editorTextField.text)
        }
    }

    @Test
    fun unresolvedTextPreservesDraftAndSelectedItem() {
        edt {
            val comboBox = createComboBox(createCustomValues = false)
            val project = choice("project", text = "Project")
            comboBox.addItem(project)
            comboBox.selectedItem = project

            comboBox.editorTextField.text = "unknown"

            assertEquals(project, comboBox.selectedItem)
            assertEquals("unknown", comboBox.editorTextField.text)
        }
    }

    @Test
    fun enterCommitResolvesTypedTextToExistingItem() {
        edt {
            val comboBox = createComboBox()
            val path = choice("path", text = "PATH")
            val project = choice("project", text = "Project")
            comboBox.addItem(project)
            comboBox.addItem(path)
            comboBox.selectedItem = project

            comboBox.editorTextField.text = "PATH"
            pressEnter(comboBox)

            assertSame(path, comboBox.selectedItem)
            assertEquals("PATH", comboBox.editorTextField.text)
            assertEquals(2, comboBox.model.size)
        }
    }

    @Test
    fun enterCommitWithCustomTextCommitsCustomItemWithoutAddingToModel() {
        edt {
            val comboBox = createComboBox(createCustomValues = true)
            val project = choice("project", text = "Project")
            comboBox.addItem(project)
            comboBox.selectedItem = project

            comboBox.editorTextField.text = "/usr/local/bin/mill"
            pressEnter(comboBox)

            assertEquals("custom:/usr/local/bin/mill", (comboBox.selectedItem as Choice).id)
            assertEquals("/usr/local/bin/mill", comboBox.editorTextField.text)
            assertEquals(1, comboBox.model.size)
        }
    }

    @Test
    fun enterCommitWithUnresolvedTextKeepsDraftAndSelection() {
        edt {
            val comboBox = createComboBox(createCustomValues = false)
            val project = choice("project", text = "Project")
            comboBox.addItem(project)
            comboBox.selectedItem = project

            comboBox.editorTextField.text = "unknown"
            pressEnter(comboBox)

            assertSame(project, comboBox.selectedItem)
            assertEquals("unknown", comboBox.editorTextField.text)
        }
    }

    @Test
    fun enterCommitWithUnresolvedTextRestoresSelectionTextWhenRejecting() {
        edt {
            val comboBox = createComboBox(
                createCustomValues = false,
                unresolvedTextPolicy = UnresolvedTextPolicy.REJECT,
            )
            val project = choice("project", text = "Project")
            comboBox.addItem(project)
            comboBox.selectedItem = project

            comboBox.editorTextField.text = "unknown"
            pressEnter(comboBox)

            assertSame(project, comboBox.selectedItem)
            assertEquals("Project", comboBox.editorTextField.text)
        }
    }

    @Test
    fun enterCommitWithDuplicateLabelsPrefersSelectedInstance() {
        edt {
            val comboBox = createComboBox()
            val first = choice("first", text = "X")
            val second = choice("second", text = "X")
            comboBox.addItem(first)
            comboBox.addItem(second)
            comboBox.selectedItem = second

            comboBox.editorTextField.text = "X"
            pressEnter(comboBox)

            assertSame(second, comboBox.selectedItem)
        }
    }

    @Test
    fun enterCommitWithDuplicateLabelsKeepsSelectedInstanceWhenRejecting() {
        edt {
            val comboBox = createComboBox(
                createCustomValues = false,
                unresolvedTextPolicy = UnresolvedTextPolicy.REJECT,
            )
            val first = choice("first", text = "X")
            val second = choice("second", text = "X")
            comboBox.addItem(first)
            comboBox.addItem(second)
            comboBox.selectedItem = second

            comboBox.editorTextField.text = "unknown"
            pressEnter(comboBox)

            assertSame(second, comboBox.selectedItem)
            assertEquals("X", comboBox.editorTextField.text)
        }
    }

    @Test
    fun enterCommitWithEmptyTextKeepsSelectionAndDraft() {
        edt {
            val comboBox = createComboBox(createCustomValues = false)
            val project = choice("project", text = "Project")
            comboBox.addItem(project)
            comboBox.selectedItem = project

            comboBox.editorTextField.text = ""
            pressEnter(comboBox)

            assertSame(project, comboBox.selectedItem)
            assertEquals("", comboBox.editorTextField.text)
        }
    }

    @Test
    fun enterCommitWithUnresolvedTextAndNoSelectionClearsDraftWhenRejecting() {
        edt {
            val comboBox = createComboBox(
                createCustomValues = false,
                unresolvedTextPolicy = UnresolvedTextPolicy.REJECT,
            )

            comboBox.editorTextField.text = "unknown"
            pressEnter(comboBox)

            assertNull(comboBox.selectedItem)
            assertEquals("", comboBox.editorTextField.text)
        }
    }

    @Test
    fun bindSelectedItemSynchronizesBothDirections() {
        edt {
            val comboBox = createComboBox()
            val project = choice("project", text = "Project")
            val path = choice("path", text = "PATH")
            comboBox.addItem(project)
            comboBox.addItem(path)
            val property = PropertyGraph().property<Choice?>(project)
            panel {
                row {
                    cell(comboBox).bindSelectedItem(property)
                }
            }

            comboBox.selectedItem = path
            assertEquals(path, property.get())

            property.set(project)
            assertEquals(project, comboBox.selectedItem)
        }
    }

    @Test
    fun bindSelectedItemReceivesCommittedCustomItem() {
        edt {
            val comboBox = createComboBox(createCustomValues = true)
            val project = choice("project", text = "Project")
            comboBox.addItem(project)
            comboBox.selectedItem = project
            val property = PropertyGraph().property<Choice?>(project)
            panel {
                row {
                    cell(comboBox).bindSelectedItem(property)
                }
            }

            comboBox.editorTextField.text = "/usr/local/bin/mill"
            pressEnter(comboBox)

            assertEquals("custom:/usr/local/bin/mill", (property.get() as Choice).id)
        }
    }

    @Test
    fun factoryAppliesAdapterPolicyAndAdapterDerivedRenderer() {
        lateinit var box: EditableHintedComboBox<Choice>
        edt {
            panel {
                row {
                    box = editableHintedComboBox(
                        listOf(choice("project", text = "Project")),
                        adapter = TestChoiceAdapter(createCustomValues = false),
                        unresolvedTextPolicy = UnresolvedTextPolicy.REJECT,
                    ).component
                }
            }
        }

        assertSame(UnresolvedTextPolicy.REJECT, box.unresolvedTextPolicy)
        assertNotNull(box.renderer)
        assertEquals("Project", box.editorTextField.text)
        val rendered = box.renderer.getListCellRendererComponent(JList(), choice("path", text = "PATH"), 0, false, false)
        assertEquals("PATH", (rendered as JLabel).text)
    }

    @Test
    fun bindTextSynchronizesEditorDraftToPropertyWithoutChangingSelectedItem() {
        edt {
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
    }

    @Test
    fun bindTextSynchronizesPropertyToEditorDraftWithoutChangingSelectedItem() {
        edt {
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
    }

    @Test
    fun bindTextReceivesSelectedItemTextWriteBack() {
        edt {
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
    }

    @Test
    fun bindTextTracksVisibleEditorAfterUpdateUi() {
        edt {
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
            comboBox.editorTextField.text = "PATH"

            assertSame(boundEditor, comboBox.editorTextField)
            assertSame(comboBox.editorTextField, comboBox.editor.editorComponent)
            assertEquals("PATH", property.get())
            assertEquals(project, comboBox.selectedItem)
        }
    }

    @Test
    fun boundHintsUpdateVisibleEditorAfterUpdateUi() {
        edt {
            val comboBox = createComboBox()
            val propertyGraph = PropertyGraph()
            val trailingHintProperty = propertyGraph.property("")
            val rightHintProperty = propertyGraph.property("")
            panel {
                row {
                    cell(comboBox)
                        .bindTrailingHint(trailingHintProperty)
                        .bindRightHint(rightHintProperty)
                }
            }
            val boundEditor = comboBox.editorTextField

            comboBox.updateUI()
            trailingHintProperty.set("/repo/mill")
            rightHintProperty.set("1.0.0")

            assertSame(boundEditor, comboBox.editorTextField)
            assertSame(comboBox.editorTextField, comboBox.editor.editorComponent)
            assertEquals("/repo/mill", comboBox.trailingHint)
            assertEquals("1.0.0", comboBox.rightHint)
        }
    }

    @Test
    fun boundHintsDoNotCommitDraftText() {
        edt {
            val comboBox = createComboBox()
            val project = choice("project", text = "Project")
            comboBox.addItem(project)
            comboBox.selectedItem = project
            val propertyGraph = PropertyGraph()
            val trailingHintProperty = propertyGraph.property("")
            val rightHintProperty = propertyGraph.property("")
            panel {
                row {
                    cell(comboBox)
                        .bindTrailingHint(trailingHintProperty)
                        .bindRightHint(rightHintProperty)
                }
            }

            comboBox.editorTextField.text = "PATH"
            trailingHintProperty.set("/repo/mill")
            rightHintProperty.set("1.0.0")

            assertEquals("/repo/mill", comboBox.trailingHint)
            assertEquals("1.0.0", comboBox.rightHint)
            assertEquals(project, comboBox.selectedItem)
        }
    }

    @Test
    // The extension tooltip is a decorative fixture value that only reserves editor space like
    // the production usage does; it is never shown to a user.
    @Suppress("HardCodedStringLiteral")
    fun rightHintAndTrailingExtensionReserveEditorSpace() {
        edt {
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
    }

    private fun createComboBox(
        createCustomValues: Boolean = true,
        unresolvedTextPolicy: UnresolvedTextPolicy = UnresolvedTextPolicy.KEEP_DRAFT,
    ): EditableHintedComboBox<Choice> {
        return EditableHintedComboBox(
            adapter = TestChoiceAdapter(createCustomValues),
            unresolvedTextPolicy = unresolvedTextPolicy,
        )
    }

    private fun choice(
        id: String,
        text: String = id,
        hint: String? = null,
    ): Choice {
        return Choice(id = id, text = text, hint = hint)
    }

    private fun edt(block: () -> Unit) {
        SwingUtilities.invokeAndWait { block() }
    }

    /** Commits the editor draft; must be called on the EDT, e.g. inside [edt]. */
    private fun pressEnter(comboBox: EditableHintedComboBox<*>) {
        comboBox.editorTextField.postActionEvent()
    }

    private data class Choice(
        val id: String,
        val text: String,
        val hint: String?,
    )

    private class TestChoiceAdapter(
        private val createCustomValues: Boolean,
    ) : EditableHintedComboBoxAdapter<Choice> {
        override fun text(item: Choice): String {
            return item.text
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
