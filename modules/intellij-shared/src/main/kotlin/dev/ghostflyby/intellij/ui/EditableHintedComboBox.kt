/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.intellij.ui

import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.JBColor
import com.intellij.ui.components.fields.ExtendableTextComponent
import com.intellij.ui.components.fields.ExtendableTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtilities
import java.awt.*
import java.awt.geom.Rectangle2D
import javax.swing.ComboBoxModel
import javax.swing.DefaultComboBoxModel
import javax.swing.JTextField
import javax.swing.plaf.basic.BasicComboBoxEditor
import kotlin.math.ceil

/**
 * Converts between model items and editor text.
 *
 * Implementations only need to handle *custom* text: the combo box itself first resolves the typed
 * text against the model items by comparing it with [text]. Only when no item matches does
 * [fromText] decide what the text commits to; returning `null` leaves the decision to
 * [EditableHintedComboBox.unresolvedTextPolicy].
 */
public fun interface EditableHintedComboBoxAdapter<T> {
    /**
     * The editor text shown for [item]. Defaults to `toString()`.
     */
    public fun text(item: T): String = item.toString()

    /**
     * Builds the value to commit for text that matches no existing item, or `null` to keep the
     * text unresolved.
     */
    public fun fromText(text: String): T?
}

/**
 * What happens when the editor text is committed (Enter) but resolves to no item.
 *
 * Note: like with stock IntelliJ editable combo boxes, pressing Enter also activates the dialog's
 * default button after the commit; the component cannot suppress that platform behavior.
 */
public enum class UnresolvedTextPolicy {
    /**
     * Keep the typed text and the current selection unchanged.
     */
    KEEP_DRAFT,

    /**
     * Discard the typed text and restore the editor text of the current selection.
     */
    REJECT,
}

public class EditableHintedComboBox<E>(
    model: ComboBoxModel<E> = DefaultComboBoxModel(),
    public val adapter: EditableHintedComboBoxAdapter<E> = defaultAdapter(),
    public val unresolvedTextPolicy: UnresolvedTextPolicy = UnresolvedTextPolicy.KEEP_DRAFT,
) : ComboBox<E>(model) {
    private val hintedEditorTextField = HintedTextField().apply { border = null }
    private val comboBoxEditor = object : BasicComboBoxEditor() {
        override fun createEditorComponent(): JTextField {
            return hintedEditorTextField
        }

        override fun setItem(anObject: Any?) {
            if (anObject == null) {
                super.setItem(null)
                return
            }
            @Suppress("UNCHECKED_CAST")
            val text = adapter.text(anObject as E)
            // Guard from BasicComboBoxEditor (JDK 4203968): setText replaces the document even for
            // an equal string, resetting the caret and re-firing document listeners.
            if (text != editor.text) {
                editor.text = text
            }
        }

        /**
         * The editor never reports an unresolved value: text matching an existing item resolves to
         * that item, custom text goes through [EditableHintedComboBoxAdapter.fromText], and
         * anything else falls back to the current selection. The IntelliJ combo commit path
         * (`MyEditor` -> `setSelectedItem(getItem())`) reads this value, so committing an
         * unresolved draft can neither null the selection nor clear the editor.
         */
        override fun getItem(): Any? {
            val resolved = resolveEditorText()
            return resolved ?: selectedItem
        }
    }

    init {
        isSwingPopup = false
        // IntelliJ's `ComboBox.setEditor` wraps the editor so `JComboBox.actionPerformed` never
        // receives editor actions: the wrapper commits through `setSelectedItem(getItem())` on its
        // own listener. Register ours before that wrapper exists, so REJECT can restore the
        // selected item's text before the commit runs.
        hintedEditorTextField.addActionListener {
            if (unresolvedTextPolicy == UnresolvedTextPolicy.REJECT && resolveEditorText() == null) {
                comboBoxEditor.setItem(this.selectedItem)
            }
        }
        isEditable = true
        super.setEditor(comboBoxEditor)
    }

    private fun resolveEditorText(): E? {
        val typed = editorTextField.text
        if (typed.isEmpty()) {
            return null
        }
        return findItemByText(typed) ?: adapter.fromText(typed)
    }

    private fun findItemByText(text: String): E? {
        // Prefer the selected instance when its text matches: with duplicate display labels a
        // commit must not silently switch to the first look-alike item.
        @Suppress("UNCHECKED_CAST")
        val selected = selectedItem as? E
        if (selected != null && adapter.text(selected) == text) {
            return selected
        }
        val items = model
        for (index in 0 until items.size) {
            val item = items.getElementAt(index) ?: continue
            if (adapter.text(item) == text) {
                return item
            }
        }
        return null
    }

    public val editorTextField: HintedTextField get() = hintedEditorTextField
    public var trailingHint: String by hintedEditorTextField::trailingHint
    public var rightHint: String by hintedEditorTextField::rightHint

    public companion object {
        /**
         * An adapter that renders items with `toString()` and never creates custom values;
         * committing unmatched text is governed by [UnresolvedTextPolicy] instead.
         */
        public fun <T> defaultAdapter(): EditableHintedComboBoxAdapter<T> =
            EditableHintedComboBoxAdapter { null }
    }
}

/**
 * A text field that paints an inline [trailingHint] after the typed text and a right-aligned
 * [rightHint] at the right edge, reserving editor space for both. The right hint is painted with
 * the error color while the field outline marks an error.
 */
public class HintedTextField : ExtendableTextField() {
    private companion object {
        const val OUTLINE_PROPERTY = "JComponent.outline"
        const val ERROR_OUTLINE = "error"
        const val GAP = 6
        const val OUTER_GAP = 8
    }

    private var trailingHintValue: String? = null
    private var rightHintValue: String? = null

    /**
     * The margin set from outside; the computed hint/extension reserve is applied on top of it.
     */
    private var externalMargin: Insets? = null
    private var applyingComputedMargin = false

    public var trailingHint: String
        get() = trailingHintValue.orEmpty()
        set(value) {
            trailingHintValue = value
            revalidate()
            repaint()
        }

    public var rightHint: String
        get() = rightHintValue.orEmpty()
        set(value) {
            rightHintValue = value
            updateMargins()
            revalidate()
            repaint()
        }

    init {
        updateMargins()
        addPropertyChangeListener(OUTLINE_PROPERTY) {
            repaint()
        }
    }

    override fun updateUI() {
        super.updateUI()
        // Re-apply the computed reserve: JBUI scales may have changed (zoom, DPI).
        updateMargins()
    }

    override fun setMargin(margin: Insets?) {
        if (applyingComputedMargin) {
            super.setMargin(margin)
            return
        }
        externalMargin = margin
        updateMargins()
    }

    private fun textWidth(text: String): Int {
        if (text.isEmpty()) {
            return 0
        }
        val metrics = getFontMetrics(font)
        return metrics.stringWidth(text)
    }

    private fun updateMargins() {
        val rightText = rightHint
        val rightExtensionWidth = rightExtensionReservedWidth()
        val hintWidth = if (rightText.isEmpty()) 0 else JBUI.scale(GAP) + textWidth(rightText)
        val rightReserved = JBUI.scale(OUTER_GAP) + rightExtensionWidth + hintWidth
        val base = externalMargin
        applyingComputedMargin = true
        try {
            super.setMargin(
                JBUI.insets(
                    base?.top ?: 0,
                    (base?.left ?: 0) + JBUI.scale(OUTER_GAP),
                    base?.bottom ?: 0,
                    (base?.right ?: 0) + rightReserved,
                ),
            )
        } finally {
            applyingComputedMargin = false
        }
    }

    private fun rightExtensionReservedWidth(): Int {
        val rightExtensions = runCatching { extensions }
            .getOrNull()
            .orEmpty()
            .filterNot { it.isIconBeforeText }
        if (rightExtensions.isEmpty()) {
            return 0
        }
        return rightExtensions.sumOf { extension ->
            extension.buttonSize?.width ?: extension.preferredSpace
        }
    }

    override fun setFont(font: Font?) {
        super.setFont(font)
        if (font != null) {
            updateMargins()
        }
    }

    override fun setExtensions(vararg extensions: ExtendableTextComponent.Extension) {
        super.setExtensions(*extensions)
        updateMargins()
    }

    override fun addExtension(extension: ExtendableTextComponent.Extension) {
        super.addExtension(extension)
        updateMargins()
    }

    override fun removeExtension(extension: ExtendableTextComponent.Extension) {
        super.removeExtension(extension)
        updateMargins()
    }

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)

        val trailingText = trailingHintValue.orEmpty()
        val rightText = rightHintValue.orEmpty()
        if (trailingText.isEmpty() && rightText.isEmpty()) {
            return
        }

        val g2 = graphics.create() as? Graphics2D ?: return
        try {
            g2.font = font

            val gap = JBUI.scale(GAP)
            val outerGap = JBUI.scale(OUTER_GAP)
            val metrics: FontMetrics = g2.getFontMetrics(font)
            val baseline = (height - metrics.height) / 2 + metrics.ascent
            val rightExtensionWidth = rightExtensionReservedWidth()
            val rightHintX = if (rightText.isEmpty()) {
                Int.MIN_VALUE
            } else {
                width - outerGap - rightExtensionWidth - textWidth(rightText)
            }

            if (trailingText.isNotEmpty()) {
                g2.color = JBColor.namedColor(
                    "TextField.inactiveForeground",
                    JBColor.namedColor("Component.infoForeground", JBColor.GRAY),
                )
                val endX = textEndX()
                val hintX = endX + gap
                val rightLimit = if (rightText.isEmpty()) {
                    width - outerGap - rightExtensionWidth - gap
                } else {
                    rightHintX - gap
                }

                if (hintX < rightLimit) {
                    UIUtilities.drawString(this, g2, trailingText, hintX, baseline)
                }
            }

            if (rightText.isNotEmpty()) {
                // String comparison matches DarculaUIUtil.getOutline: the platform honors the
                // outline client property only in its String form.
                g2.color = if (getClientProperty(OUTLINE_PROPERTY) == ERROR_OUTLINE) {
                    JBColor.namedColor("Label.errorForeground", JBColor.RED)
                } else {
                    JBColor.namedColor(
                        "TextField.inactiveForeground",
                        JBColor.namedColor("Component.infoForeground", JBColor.GRAY),
                    )
                }
                UIUtilities.drawString(this, g2, rightText, rightHintX, baseline)
            }
        } finally {
            g2.dispose()
        }
    }

    private fun textEndX(): Int {
        return runCatching {
            val shape: Rectangle2D = modelToView2D(document.length)
            ceil(shape.x).toInt()
        }.getOrElse {
            insets.left + textWidth(text.orEmpty())
        }
    }
}
