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

import com.intellij.icons.AllIcons
import com.intellij.ide.projectView.PresentationData
import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.Key
import com.intellij.openapi.externalSystem.model.project.LibraryDependencyData
import com.intellij.openapi.externalSystem.model.project.LibraryPathType
import com.intellij.openapi.externalSystem.model.task.TaskData
import com.intellij.openapi.externalSystem.util.Order
import com.intellij.openapi.externalSystem.view.ExternalProjectsView
import com.intellij.openapi.externalSystem.view.ExternalSystemNode
import com.intellij.openapi.externalSystem.view.ExternalSystemViewContributor
import com.intellij.openapi.externalSystem.view.TaskNode
import com.intellij.util.containers.MultiMap
import java.io.File

private const val MILL_TASKS_NODE_ORDER: Int = 10
private const val MILL_TASK_OWNER_NODE_ORDER: Int = MILL_TASKS_NODE_ORDER + 1

internal class MillViewContributor : ExternalSystemViewContributor() {
    override fun getSystemId() = MillConstants.systemId

    override fun getKeys(): List<Key<*>> = listOf(MillTasksData.key)

    override fun createNodes(
        externalProjectsView: ExternalProjectsView,
        dataNodes: MultiMap<Key<*>?, DataNode<*>?>?,
    ): List<ExternalSystemNode<*>> {
        val millTaskNodes = dataNodes?.get(MillTasksData.key).orEmpty()
        return millTaskNodes.mapNotNull { dataNode ->
            @Suppress("UNCHECKED_CAST")
            (dataNode as? DataNode<MillTasksData>)?.let { MillTasksRootNode(externalProjectsView, it) }
        }
    }

    override fun getDisplayName(node: DataNode<*>): String? {
        return when (val data = node.data) {
            is TaskData -> prettifyTaskName(data.name)
            is LibraryDependencyData -> prettifyLibraryName(data)
            else -> null
        }
    }

    private fun prettifyTaskName(taskName: String): String {
        val trimmed = taskName.trim()
        if (trimmed.isEmpty()) {
            return trimmed.ifEmpty { taskName }
        }

        if (trimmed.startsWith("show ")) {
            val shownTarget = trimmed.removePrefix("show ").trim()
            return "show ${prettifyDottedTarget(shownTarget)}"
        }

        if (' ' in trimmed) {
            return trimmed
        }

        return prettifyDottedTarget(trimmed)
    }

    private fun prettifyDottedTarget(target: String): String {
        val prefix = target.substringBeforeLast('.', missingDelimiterValue = "")
        val action = target.substringAfterLast('.', missingDelimiterValue = target)
        if (prefix.isBlank() || prefix == target) {
            return target
        }

        val moduleLabel = when (prefix) {
            "__" -> "all modules"
            else -> prefix
        }
        return "$moduleLabel / $action"
    }

    private fun prettifyLibraryName(data: LibraryDependencyData): String? {
        val externalName = data.externalName.trim()
        if (externalName.isNotEmpty()) {
            return externalName
        }

        val binaryPaths = data.target.getPaths(LibraryPathType.BINARY)
        if (binaryPaths.size != 1) {
            return null
        }

        return File(binaryPaths.first()).name
    }
}

@Order(MILL_TASKS_NODE_ORDER)
internal class MillTasksRootNode(
    private val view: ExternalProjectsView,
    private val tasksDataNode: DataNode<MillTasksData>,
) : ExternalSystemNode<MillTasksData>(view, null, tasksDataNode) {
    override fun getName(): String = "Tasks"

    override fun isVisible(): Boolean = super.isVisible() && hasChildren()

    override fun update(presentation: PresentationData) {
        super.update(presentation)
        presentation.setIcon(AllIcons.Nodes.ConfigFolder)
    }

    override fun doBuildChildren(): List<ExternalSystemNode<*>> {
        val tasksData = data ?: return emptyList()
        val taskNodes = tasksDataNode.children
            .mapNotNull { child ->
                @Suppress("UNCHECKED_CAST")
                child as? DataNode<TaskData>
            }
            .orEmpty()
        val viewModel = MillTaskViewStructure.build(tasksData.projectName, taskNodes)
        return listOf(
            MillTaskOwnerNode(
                view = view,
                displayName = viewModel.projectName,
                prefix = "",
                taskNodes = viewModel.projectTasks,
                childrenModels = viewModel.moduleOwners,
                isProjectRoot = true,
            )
        )
    }
}

@Order(MILL_TASK_OWNER_NODE_ORDER)
internal class MillTaskOwnerNode(
    private val view: ExternalProjectsView,
    private val displayName: String,
    private val prefix: String,
    private val taskNodes: List<DataNode<TaskData>>,
    private val childrenModels: List<MillTaskOwnerViewModel>,
    private val isProjectRoot: Boolean = false,
) : ExternalSystemNode<Any>(view, null, null) {
    override fun getName(): String = displayName

    override fun update(presentation: PresentationData) {
        super.update(presentation)
        presentation.setIcon(uiAware.projectIcon)
        val hint = if (isProjectRoot || prefix.isEmpty()) "root" else null
        setNameAndTooltip(
            presentation,
            displayName,
            prefix.ifEmpty { null },
            hint,
        )
    }

    override fun doBuildChildren(): List<ExternalSystemNode<*>> {
        val children = mutableListOf<ExternalSystemNode<*>>()
        if (view.groupTasks) {
            val groups = taskNodes.groupBy { taskNode ->
                taskNode.data.group?.trim().orEmpty().ifEmpty { "other" }
            }
            children += groups.entries.map { (group, groupedTasks) ->
                MillTaskGroupNode(view, group, groupedTasks)
            }
        } else {
            children += taskNodes.map { TaskNode(view, it) }
        }

        children += childrenModels.map { child ->
            MillTaskOwnerNode(
                view = view,
                displayName = child.displayName,
                prefix = child.prefix,
                taskNodes = child.tasks,
                childrenModels = child.children,
            )
        }
        return children
    }

    override fun isVisible(): Boolean = super.isVisible() && (taskNodes.isNotEmpty() || childrenModels.isNotEmpty())
}

@Order(MILL_TASKS_NODE_ORDER)
internal class MillTaskGroupNode(
    private val view: ExternalProjectsView,
    private val groupName: String,
    private val taskNodes: List<DataNode<TaskData>>,
) : ExternalSystemNode<Any>(view, null, null) {
    override fun getName(): String = groupName

    override fun update(presentation: PresentationData) {
        super.update(presentation)
        presentation.setIcon(AllIcons.Nodes.ConfigFolder)
    }

    override fun doBuildChildren(): List<ExternalSystemNode<*>> {
        return taskNodes.map { TaskNode(view, it) }
    }

    override fun compareTo(node: ExternalSystemNode<*>): Int {
        return if (groupName.equals("other", ignoreCase = true)) 1 else super.compareTo(node)
    }
}
