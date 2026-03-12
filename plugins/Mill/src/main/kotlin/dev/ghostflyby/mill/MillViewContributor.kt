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
import com.intellij.openapi.externalSystem.model.Key
import com.intellij.openapi.externalSystem.model.project.LibraryDependencyData
import com.intellij.openapi.externalSystem.model.project.LibraryPathType
import com.intellij.openapi.externalSystem.model.task.TaskData
import com.intellij.openapi.externalSystem.view.ExternalProjectsView
import com.intellij.openapi.externalSystem.view.ExternalSystemNode
import com.intellij.openapi.externalSystem.view.ExternalSystemViewContributor
import com.intellij.util.containers.MultiMap
import java.io.File

internal class MillViewContributor : ExternalSystemViewContributor() {
    override fun getSystemId() = MillConstants.systemId

    override fun getKeys(): List<Key<*>> = emptyList()

    override fun createNodes(
        externalProjectsView: ExternalProjectsView,
        dataNodes: MultiMap<Key<*>?, DataNode<*>?>?,
    ): List<ExternalSystemNode<*>> = emptyList()

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
