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

import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.task.TaskData

internal data class MillTaskTreeNodeModel(
    val displayName: String,
    val taskNode: DataNode<TaskData>?,
    val children: List<MillTaskTreeNodeModel>,
)

internal object MillTaskTreeStructure {
    fun build(taskNodes: Collection<DataNode<TaskData>>): List<MillTaskTreeNodeModel> {
        val root = MutableTaskNode("")
        taskNodes.sortedBy { it.data.name }.forEach { taskNode ->
            insertTask(root, taskNode)
        }
        return root.toChildrenViewModels()
    }

    private fun insertTask(root: MutableTaskNode, taskNode: DataNode<TaskData>) {
        val segments = taskNode.data.name
            .split('.')
            .filter { it.isNotEmpty() }
            .ifEmpty { listOf(taskNode.data.name) }
        var current = root
        segments.forEach { segment ->
            current = current.children.getOrPut(segment) { MutableTaskNode(segment) }
        }
        current.taskNode = taskNode
    }

    private class MutableTaskNode(
        private val displayName: String,
    ) {
        var taskNode: DataNode<TaskData>? = null
        val children: LinkedHashMap<String, MutableTaskNode> = linkedMapOf()

        fun toChildrenViewModels(): List<MillTaskTreeNodeModel> {
            return children.values
                .map { child ->
                    MillTaskTreeNodeModel(
                        displayName = child.displayName,
                        taskNode = child.taskNode,
                        children = child.toChildrenViewModels(),
                    )
                }
        }
    }
}
