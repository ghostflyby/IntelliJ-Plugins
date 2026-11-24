/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mill

import com.intellij.execution.configurations.SimpleJavaParameters
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.externalSystem.ExternalSystemAutoImportAware
import com.intellij.openapi.externalSystem.ExternalSystemManager
import com.intellij.openapi.externalSystem.ExternalSystemUiAware
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.service.project.ExternalSystemProjectResolver
import com.intellij.openapi.externalSystem.service.ui.DefaultExternalSystemUiAware
import com.intellij.openapi.externalSystem.task.ExternalSystemTaskManager
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Pair
import com.intellij.util.Function
import dev.ghostflyby.mill.project.MillProjectResolverSupport
import dev.ghostflyby.mill.settings.MillExecutionSettings
import dev.ghostflyby.mill.settings.MillExecutionTargets
import dev.ghostflyby.mill.settings.MillExecutableConfigurationUtil
import dev.ghostflyby.mill.settings.MillExecutableSource
import dev.ghostflyby.mill.settings.MillLocalSettings
import dev.ghostflyby.mill.settings.MillProjectSettings
import dev.ghostflyby.mill.settings.MillSettings
import dev.ghostflyby.mill.settings.MillSettingsListener
import kotlinx.coroutines.Deferred
import kotlinx.serialization.StringFormat
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Path
import javax.swing.Icon

internal class MillExternalSystemManager : ExternalSystemManager<
    MillProjectSettings,
    MillSettingsListener,
    MillSettings,
    MillLocalSettings,
    MillExecutionSettings,
    >,
    ExternalSystemUiAware,
    ExternalSystemAutoImportAware {

    override fun getSystemId(): ProjectSystemId = MillConstants.SYSTEM_ID

    override fun getSettingsProvider(): Function<Project, MillSettings> =
        Function(MillSettings::getInstance)

    override fun getLocalSettingsProvider(): Function<Project, MillLocalSettings> =
        Function(MillLocalSettings::getInstance)

    // The platform declares a raw `Function` here (no type arguments), so the override is
    // unchecked at the call sites the framework performs; keep the parameterized shape.
    @Suppress("UNCHECKED_CAST")
    override fun getExecutionSettingsProvider(): Function<Pair<Project, String>, MillExecutionSettings> =
        Function { pair: Pair<Any?, Any?> ->
            val linkedSettings = MillSettings.getInstance(pair.first as Project).getLinkedProjectSettings(pair.second as String)
            MillExecutionSettings().apply {
                target = MillExecutionTargets.detect(Path.of(pair.second.toString()))
                if (linkedSettings == null) {
                    millExecutableSource = MillExecutableSource.PROJECT_DEFAULT_SCRIPT
                    millExecutablePath = ""
                    useMillMetadataDuringImport = true
                    createPerModuleTaskNodes = true
                } else {
                    val executableConfiguration = MillExecutableConfigurationUtil.normalize(
                        linkedSettings.millExecutableSource,
                        linkedSettings.millExecutablePath,
                    )
                    millExecutableSource = executableConfiguration.source
                    millExecutablePath = executableConfiguration.manualPath
                    useMillMetadataDuringImport = linkedSettings.useMillMetadataDuringImport
                    createPerModuleTaskNodes = linkedSettings.createPerModuleTaskNodes
                }
            }
        } as Function<Pair<Project, String>, MillExecutionSettings>

    override fun getProjectResolverClass(): Class<out ExternalSystemProjectResolver<MillExecutionSettings>> =
        MillProjectResolver::class.java

    override fun getTaskManagerClass(): Class<out ExternalSystemTaskManager<MillExecutionSettings>> =
        MillTaskManager::class.java

    override fun getExternalProjectDescriptor(): FileChooserDescriptor = FileChooserDescriptorFactory.singleFileOrDir()

    override fun getProjectRepresentationName(targetProjectPath: String, rootProjectPath: String?): String =
        MillProjectResolverSupport.presentableProjectName(targetProjectPath, rootProjectPath)

    override fun getExternalProjectConfigDescriptor(): FileChooserDescriptor = FileChooserDescriptorFactory.singleFileOrDir()

    override fun getProjectIcon(): Icon = DefaultExternalSystemUiAware.INSTANCE.projectIcon

    override fun getTaskIcon(): Icon = DefaultExternalSystemUiAware.INSTANCE.taskIcon

    override fun getAffectedExternalProjectPath(changedFileOrDirPath: String, project: Project): String? {
        val linkedProjectPaths = MillSettings.getInstance(project)
            .linkedProjectsSettings
            .map(MillProjectSettings::getExternalProjectPath)
        return MillProjectResolverSupport.findAffectedExternalProjectPath(changedFileOrDirPath, linkedProjectPaths)
    }

    override fun getAffectedExternalProjectFiles(projectPath: String, project: Project): List<File> =
        MillProjectResolverSupport.findAffectedExternalProjectFiles(projectPath)

    override fun enhanceRemoteProcessing(parameters: SimpleJavaParameters) {
        val json = PathManager.getJarPathForClass(Json::class.java)
        val core = PathManager.getJarPathForClass(StringFormat::class.java)
        val coroutines = PathManager.getJarPathForClass(Deferred::class.java)

        parameters.classPath.add(json)
        parameters.classPath.add(core)
        parameters.classPath.add(coroutines)
    }
}
