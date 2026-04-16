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
import com.intellij.openapi.externalSystem.action.ExternalSystemActionUtil
import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.Key
import com.intellij.openapi.externalSystem.model.project.LibraryDependencyData
import com.intellij.openapi.externalSystem.model.project.LibraryPathType
import com.intellij.openapi.externalSystem.model.task.TaskData
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.externalSystem.util.Order
import com.intellij.openapi.externalSystem.view.ExternalProjectsView
import com.intellij.openapi.externalSystem.view.ExternalSystemNode
import com.intellij.openapi.externalSystem.view.ExternalSystemViewContributor
import com.intellij.ui.treeStructure.SimpleTree
import com.intellij.util.containers.MultiMap
import java.awt.event.InputEvent
import java.io.File

private const val MILL_TASKS_ROOT_ORDER: Int = 10
internal const val MILL_TASK_MENU_ID: String = "dev.ghostflyby.mill.taskMenu"
internal const val MILL_TASK_RUN_ACTION_ID: String = "dev.ghostflyby.mill.runTask"

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
        val data = node.data
        return when (data) {
            is LibraryDependencyData -> prettifyLibraryName(data)
            is TaskData -> data.name
            else -> null
        }
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

@Order(MILL_TASKS_ROOT_ORDER)
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
        val taskNodes = tasksDataNode.children
            .mapNotNull { child ->
                @Suppress("UNCHECKED_CAST")
                child as? DataNode<TaskData>
            }
        return MillTaskTreeStructure.build(taskNodes).map { MillTaskPathNode(view, it) }
    }
}

internal class MillTaskPathNode(
    private val view: ExternalProjectsView,
    private val model: MillTaskTreeNodeModel,
) : ExternalSystemNode<Any>(view, null, null) {
    internal val taskData: TaskData?
        get() = model.taskNode?.data

    override fun getName(): String = model.displayName

    override fun update(presentation: PresentationData) {
        super.update(presentation)
        presentation.setIcon(
            if (model.children.isEmpty()) view.uiAware.taskIcon else AllIcons.Nodes.ConfigFolder,
        )
        val description = taskData?.description?.takeIf(String::isNotBlank)
        if (description != null) {
            setNameAndTooltip(presentation, model.displayName, description, null as String?)
        }
    }

    override fun doBuildChildren(): List<ExternalSystemNode<*>> {
        return model.children.map { child -> MillTaskPathNode(view, child) }
    }

    override fun isAlwaysLeaf(): Boolean = model.children.isEmpty()

    override fun getMenuId(): String? = if (taskData == null) null else MILL_TASK_MENU_ID

    override fun getActionId(): String? = if (taskData == null) null else MILL_TASK_RUN_ACTION_ID

    override fun handleDoubleClickOrEnter(tree: SimpleTree, inputEvent: InputEvent) {
        runTask(taskData)
    }

    internal fun runTask(taskData: TaskData?) {
        val task = taskData ?: return
        val taskExecutionInfo = ExternalSystemActionUtil.buildTaskInfo(task)
        ExternalSystemUtil.runTask(
            taskExecutionInfo.settings,
            taskExecutionInfo.executorId,
            view.project,
            view.systemId,
        )
    }
}
