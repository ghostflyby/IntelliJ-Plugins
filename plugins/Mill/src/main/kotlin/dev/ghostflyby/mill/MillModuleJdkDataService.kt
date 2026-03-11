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
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.Key
import com.intellij.openapi.externalSystem.model.ProjectKeys
import com.intellij.openapi.externalSystem.model.project.ModuleData
import com.intellij.openapi.externalSystem.model.project.ProjectData
import com.intellij.openapi.externalSystem.service.project.IdeModifiableModelsProvider
import com.intellij.openapi.externalSystem.service.project.manage.AbstractProjectDataService
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFileManager
import java.nio.file.Path
import kotlin.io.path.exists

internal class MillModuleJdkDataService : AbstractProjectDataService<ModuleData, Module>() {
    override fun getTargetDataKey(): Key<ModuleData> = ProjectKeys.MODULE

    override fun importData(
        toImport: Collection<DataNode<ModuleData>>,
        projectData: ProjectData?,
        project: Project,
        modelsProvider: IdeModifiableModelsProvider,
    ) {
        for (dataNode in toImport) {
            val moduleData = dataNode.data
            val ideModule = modelsProvider.findIdeModule(moduleData) ?: continue
            configureModuleJdk(ideModule, moduleData, modelsProvider)
        }
    }

    private fun configureModuleJdk(
        module: Module,
        data: ModuleData,
        modelsProvider: IdeModifiableModelsProvider,
    ) {
        val modifiableRootModel = modelsProvider.getModifiableRootModel(module)
        val jdkHomePath = data.getProperty(MillModuleJdkHomeProperty)
        if (jdkHomePath == null) {
            modifiableRootModel.inheritSdk()
            return
        }

        val projectSdk = ProjectRootManager.getInstance(module.project).projectSdk
        val sdk = findOrCreateSdk(jdkHomePath)
        when {
            sdk == null -> modifiableRootModel.inheritSdk()
            sameSdkHome(projectSdk, sdk) -> modifiableRootModel.inheritSdk()
            else -> modifiableRootModel.sdk = sdk
        }
    }

    private fun findOrCreateSdk(jdkHomePath: String): Sdk? {
        val sdkTable = ProjectJdkTable.getInstance()
        val javaSdk = JavaSdk.getInstance()
        val existingSdk = sdkTable.getSdksOfType(javaSdk)
            .firstOrNull { sdk -> sameSdkHomePath(sdk.homePath, jdkHomePath) }
        if (existingSdk != null) {
            return existingSdk
        }

        if (!isValidJavaHome(javaSdk, jdkHomePath)) {
            LOG.warn("Skipping Mill module JDK import because `$jdkHomePath` is not a valid JDK home")
            return null
        }

        val createdSdk = createSdk(javaSdk, jdkHomePath)
        val application = ApplicationManager.getApplication()
        application.invokeAndWait {
            application.runWriteAction {
                sdkTable.addJdk(createdSdk)
            }
        }
        return createdSdk
    }

    private fun createSdk(javaSdk: JavaSdk, jdkHomePath: String): Sdk {
        VirtualFileManager.getInstance().refreshAndFindFileByNioPath(Path.of(jdkHomePath))
        val existingNames = ProjectJdkTable.getInstance()
            .getSdksOfType(javaSdk)
            .mapTo(linkedSetOf(), Sdk::getName)
        val baseName = javaSdk.suggestSdkName(null, jdkHomePath).ifBlank { "Mill JDK" }
        val uniqueName = MillModuleJdkSupport.createUniqueSdkName(baseName, existingNames)
        return javaSdk.createJdk(uniqueName, jdkHomePath)
    }

    private fun isValidJavaHome(javaSdk: JavaSdk, jdkHomePath: String): Boolean {
        val path = runCatching { Path.of(jdkHomePath) }.getOrNull() ?: return false
        if (!path.exists()) {
            return false
        }
        return javaSdk.isValidSdkHome(jdkHomePath)
    }

    private fun sameSdkHome(projectSdk: Sdk?, moduleSdk: Sdk): Boolean = sameSdkHomePath(projectSdk?.homePath, moduleSdk.homePath)

    private fun sameSdkHomePath(left: String?, right: String?): Boolean {
        val leftPath = MillModuleJdkSupport.normalizeJdkHomePath(left)
        val rightPath = MillModuleJdkSupport.normalizeJdkHomePath(right)
        return leftPath != null && leftPath == rightPath
    }

    private companion object {
        val LOG: Logger = Logger.getInstance(MillModuleJdkDataService::class.java)
    }
}
