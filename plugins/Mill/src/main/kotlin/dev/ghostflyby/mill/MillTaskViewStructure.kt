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
import java.util.TreeMap

internal data class MillTaskOwnerViewModel(
    val prefix: String,
    val displayName: String,
    val tasks: List<DataNode<TaskData>>,
    val children: List<MillTaskOwnerViewModel>,
)

internal data class MillTaskViewModel(
    val projectName: String,
    val projectTasks: List<DataNode<TaskData>>,
    val moduleOwners: List<MillTaskOwnerViewModel>,
)

internal object MillTaskViewStructure {
    fun build(
        projectName: String,
        taskNodes: Collection<DataNode<TaskData>>,
    ): MillTaskViewModel {
        val projectTasks = mutableListOf<DataNode<TaskData>>()
        val moduleTree = ModuleTreeNode(prefix = "", displayName = "")

        taskNodes.forEach { taskNode ->
            val ownerPrefix = ownerPrefix(taskNode.data.name)
            if (ownerPrefix == null) {
                projectTasks += taskNode
                return@forEach
            }

            val segments = ownerPrefix.split('.').filter(String::isNotBlank)
            var current = moduleTree
            val prefixBuilder = StringBuilder()
            segments.forEachIndexed { index, segment ->
                if (index > 0) {
                    prefixBuilder.append('.')
                }
                prefixBuilder.append(segment)
                current = current.children.computeIfAbsent(segment) {
                    ModuleTreeNode(prefix = prefixBuilder.toString(), displayName = segment)
                }
            }
            current.tasks += taskNode
        }

        return MillTaskViewModel(
            projectName = projectName,
            projectTasks = projectTasks.sortedBy { it.data.name.lowercase() },
            moduleOwners = moduleTree.children.values.map(ModuleTreeNode::toViewModel),
        )
    }

    internal fun ownerPrefix(taskName: String): String? {
        val commandTarget = when {
            taskName.startsWith("show ") -> taskName.removePrefix("show ").trim()
            taskName.startsWith("resolve ") -> taskName.removePrefix("resolve ").trim()
            else -> taskName.trim()
        }
        if (commandTarget.isEmpty()) {
            return null
        }

        val prefix = commandTarget.substringBeforeLast('.', missingDelimiterValue = "")
        return prefix.takeUnless {
            it.isBlank() || it == MillConstants.rootModulePrefix || it == MillConstants.moduleDiscoveryQuery
        }
    }

    private class ModuleTreeNode(
        val prefix: String,
        val displayName: String,
    ) {
        val tasks: MutableList<DataNode<TaskData>> = mutableListOf()
        val children: MutableMap<String, ModuleTreeNode> = TreeMap(String.CASE_INSENSITIVE_ORDER)

        fun toViewModel(): MillTaskOwnerViewModel {
            return MillTaskOwnerViewModel(
                prefix = prefix,
                displayName = displayName,
                tasks = tasks.sortedBy { it.data.name.lowercase() },
                children = children.values.map(ModuleTreeNode::toViewModel),
            )
        }
    }
}
