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

package dev.ghostflyby.mill

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.util.ui.tree.TreeUtil
import javax.swing.JPopupMenu
import javax.swing.JTree
import javax.swing.SwingUtilities

internal class MillRunTaskAction : DumbAwareAction("Run") {
    override fun actionPerformed(event: AnActionEvent) {
        val taskNode = selectedTaskNode(event) ?: return
        taskNode.runTask(taskNode.taskData)
    }

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = selectedTaskNode(event)?.taskData != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    private fun selectedTaskNode(event: AnActionEvent): MillTaskPathNode? {
        val tree = findContextTree(event) ?: return null
        return TreeUtil.getLastUserObject(tree.selectionPath) as? MillTaskPathNode
    }

    private fun findContextTree(event: AnActionEvent): JTree? {
        val contextComponent = event.getData(PlatformCoreDataKeys.CONTEXT_COMPONENT) ?: return null
        if (contextComponent is JTree) {
            return contextComponent
        }

        val treeAncestor = SwingUtilities.getAncestorOfClass(JTree::class.java, contextComponent) as? JTree
        if (treeAncestor != null) {
            return treeAncestor
        }

        val popupMenu = SwingUtilities.getAncestorOfClass(JPopupMenu::class.java, contextComponent) as? JPopupMenu ?: return null
        val invoker = popupMenu.invoker ?: return null
        return if (invoker is JTree) invoker else SwingUtilities.getAncestorOfClass(JTree::class.java, invoker) as? JTree
    }
}
