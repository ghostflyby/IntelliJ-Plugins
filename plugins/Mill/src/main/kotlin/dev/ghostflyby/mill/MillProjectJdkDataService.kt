/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
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
        val sdk = findOrCreateMillSdk(jdkHomePath) ?: return
        val projectRootManager = ProjectRootManager.getInstance(project)
        val application = ApplicationManager.getApplication()
        application.invokeAndWait {
            application.runWriteAction {
                projectRootManager.projectSdk = sdk
            }
        }
    }
}
