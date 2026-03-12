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

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.Key
import com.intellij.openapi.externalSystem.model.project.ProjectData
import com.intellij.openapi.externalSystem.service.project.IdeModifiableModelsProvider
import com.intellij.openapi.externalSystem.service.project.manage.AbstractProjectDataService
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ProjectRootManager

internal class MillProjectJdkDataService : AbstractProjectDataService<MillProjectJdkData, Sdk>() {
    override fun getTargetDataKey(): Key<MillProjectJdkData> = MillProjectJdkData.key

    override fun importData(
        toImport: Collection<DataNode<MillProjectJdkData>>,
        projectData: ProjectData?,
        project: Project,
        modelsProvider: IdeModifiableModelsProvider,
    ) {
        if (ProjectRootManager.getInstance(project).projectSdk != null) {
            return
        }
        val jdkHomePath = toImport.firstNotNullOfOrNull { dataNode -> dataNode.data.jdkHomePath.ifBlank { null } } ?: return
        val sdk = MillModuleJdkDataService.findOrCreateSdk(jdkHomePath) ?: return
        val projectRootManager = ProjectRootManager.getInstance(project)
        val application = ApplicationManager.getApplication()
        application.invokeAndWait {
            application.runWriteAction {
                projectRootManager.projectSdk = sdk
            }
        }
    }
}
