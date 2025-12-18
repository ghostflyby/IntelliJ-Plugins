/*
 * Copyright (c) 2025 ghostflyby
 * SPDX-FileCopyrightText: 2025 ghostflyby
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

package dev.ghostflyby.spotless.gradle

import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.openapi.components.*
import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.Key
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.model.execution.ExternalSystemTaskExecutionSettings
import com.intellij.openapi.externalSystem.model.project.ExternalEntityData
import com.intellij.openapi.externalSystem.model.project.ModuleData
import com.intellij.openapi.externalSystem.model.project.ProjectData
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode
import com.intellij.openapi.externalSystem.service.project.IdeModifiableModelsProvider
import com.intellij.openapi.externalSystem.service.project.manage.AbstractProjectDataService
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.externalSystem.util.task.TaskExecutionSpec
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.NlsSafe
import dev.ghostflyby.spotless.Spotless
import dev.ghostflyby.spotless.SpotlessDaemonHost
import org.gradle.api.Project
import org.gradle.tooling.model.idea.IdeaModule
import org.jetbrains.plugins.gradle.service.project.AbstractProjectResolverExtension
import org.jetbrains.plugins.gradle.service.project.GradleProjectResolverExtension
import org.jetbrains.plugins.gradle.util.GradleConstants
import java.nio.file.Path
import kotlin.io.path.absolute
import kotlin.io.path.absolutePathString


internal data class SpotlessGradleStateData(
    val projectDirectory: Path,
    val spotless: Boolean,
) : ExternalEntityData {
    override fun getOwner(): ProjectSystemId {
        return ProjectSystemId("GRADLE")
    }

    companion object Companion {
        @JvmField
        val KEY: Key<SpotlessGradleStateData> = Key.create(SpotlessGradleStateData::class.java, 1)
    }
}


@SpotlessIntegrationPluginInternalApi
internal class SpotlessGradleProjectResolverExtension : AbstractProjectResolverExtension(),
    GradleProjectResolverExtension {
    override fun populateModuleExtraModels(
        gradleModule: IdeaModule,
        ideModule: DataNode<ModuleData>,
    ) {
        val myModel = resolverCtx.getExtraProject(gradleModule, SpotlessGradleStateModel::class.java)
        if (myModel != null) {
            val re = ideModule.createChild(
                SpotlessGradleStateData.KEY,
                SpotlessGradleStateData(
                    gradleModule.gradleProject.projectDirectory.toPath().absolute(),
                    myModel.spotless,
                ),
            )
            Disposer.register(service<Spotless>()) {
                re.clear(true)
            }
        }
        nextResolver.populateModuleExtraModels(gradleModule, ideModule)
    }


    override fun getExtraProjectModelClasses() = setOf(SpotlessGradleStateModel::class.java)

    override fun getToolingExtensionsClasses() = setOf(SpotlessGradleStateModelBuilder::class.java)

}


internal class SpotlessGradleStateDataService : AbstractProjectDataService<SpotlessGradleStateData, Project>() {

    override fun getTargetDataKey() = SpotlessGradleStateData.KEY

    override fun importData(
        toImport: MutableCollection<out DataNode<SpotlessGradleStateData>>,
        projectData: ProjectData?,
        project: com.intellij.openapi.project.Project,
        modelsProvider: IdeModifiableModelsProvider,
    ) {
        val holder = project.service<SpotlessGradleStateHolder>()
        holder.updateFrom(toImport)
    }
}


@Service(Service.Level.PROJECT)
@State(
    name = "SpotlessGradleIntegration",
    storages = [Storage(StoragePathMacros.WORKSPACE_FILE, roamingType = RoamingType.DISABLED)],
)
internal class SpotlessGradleStateHolder
    : SerializablePersistentStateComponent<SpotlessGradleStateHolder.State>(State()) {
    fun isSpotlessEnabledForProjectDir(path: Path): Boolean {
        return state.paths.contains(path.absolutePathString())
    }

    val daemons = mutableListOf<SpotlessDaemonHost>()

    fun updateFrom(nodes: Collection<DataNode<SpotlessGradleStateData>>) {
        updateState { state ->
            state.copy(
                paths = nodes.asSequence()
                    .filter { it.data.spotless }
                    .map { it.data.projectDirectory.absolutePathString() }
                    .toSet(),
            )
        }
        nodes.forEach { it.clear(true) }
        daemons.forEach { Disposer.dispose(it) }
        daemons.clear()
    }

    var gradleDaemonVersion: @NlsSafe String
        get() = state.gradleDaemonVersion
        set(value) {
            state = state.copy(gradleDaemonVersion = value)
        }

    var gradleDaemonJar: @NlsSafe String
        get() = state.gradleDaemonJar
        set(value) {
            state = state.copy(gradleDaemonJar = value)
        }

    internal data class State(
        @JvmField val paths: Set<String> = emptySet(),
        @JvmField val gradleDaemonVersion: String = "",
        @JvmField val gradleDaemonJar: String = "",
    )
}

internal fun runGradleSpotlessDaemon(
    project: com.intellij.openapi.project.Project,
    externalProject: Path,
    unixSocketPath: Path,
    host: SpotlessDaemonHost.Unix,
) {
    val settings = ExternalSystemTaskExecutionSettings().apply {
        externalSystemIdString = GradleConstants.SYSTEM_ID.id
        externalProjectPath = externalProject.toString()
        taskNames = listOf(":spotlessDaemon")
        scriptParameters = "-Pdev.ghostflyby.spotless.daemon.unixsocket=${unixSocketPath.toAbsolutePath()}"
    }
    val exe = TaskExecutionSpec.create()
        .withProgressExecutionMode(ProgressExecutionMode.NO_PROGRESS_ASYNC)
        .withProject(project)
        .withSettings(settings)
        .withSystemId(GradleConstants.SYSTEM_ID)
        .withListener(
            object : ExternalSystemTaskNotificationListener {
                override fun onEnd(projectPath: String, id: ExternalSystemTaskId) {
                    Disposer.dispose(host)
                }

                override fun onCancel(projectPath: String, id: ExternalSystemTaskId) {
                    Disposer.dispose(host)
                }

            },
        )
        .withExecutorId(DefaultRunExecutor.EXECUTOR_ID)
        .build()
    ExternalSystemUtil.runTask(exe)
}


