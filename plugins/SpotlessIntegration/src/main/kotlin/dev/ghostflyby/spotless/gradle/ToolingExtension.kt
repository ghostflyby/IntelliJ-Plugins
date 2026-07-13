/*
 * Copyright (c) 2025-2026 ghostflyby
 * SPDX-FileCopyrightText: 2025-2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.spotless.gradle

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.*
import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.Key
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.model.project.ExternalEntityData
import com.intellij.openapi.externalSystem.model.project.ModuleData
import com.intellij.openapi.externalSystem.model.project.ProjectData
import com.intellij.openapi.externalSystem.service.project.IdeModifiableModelsProvider
import com.intellij.openapi.externalSystem.service.project.manage.AbstractProjectDataService
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.NlsSafe
import dev.ghostflyby.spotless.Spotless
import dev.ghostflyby.spotless.SpotlessDaemonHost
import org.gradle.api.Project
import org.gradle.tooling.model.idea.IdeaModule
import org.jetbrains.plugins.gradle.service.execution.toGroovyStringLiteral
import org.jetbrains.plugins.gradle.service.project.AbstractProjectResolverExtension
import org.jetbrains.plugins.gradle.service.project.GradleProjectResolverExtension
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
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
            (service<Spotless>() as? Disposable)?.let {
                Disposer.register(it) {
                    re.clear(true)
                }
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

    val daemons: MutableSet<SpotlessDaemonHost> = ConcurrentHashMap.newKeySet()

    fun hasRunningDaemons(): Boolean = daemons.isNotEmpty()

    fun releaseAllDaemons(): Int {
        val runningDaemons = daemons.toList()
        val spotless = service<Spotless>()
        runningDaemons.forEach { daemon ->
            daemons.remove(daemon)
            spotless.releaseDaemon(daemon)
        }
        return runningDaemons.size
    }

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
        releaseAllDaemons()
    }

    var gradleDaemonVersion: @NlsSafe String
        get() = state.gradleDaemonVersion
        set(value) {
            updateState {
                it.copy(gradleDaemonVersion = value)
            }
        }

    var gradleDaemonJar: @NlsSafe String
        get() = state.gradleDaemonJar
        set(value) {
            updateState {
                it.copy(gradleDaemonJar = value)
            }
        }

    internal data class State(
        @JvmField val paths: Set<String> = emptySet(),
        @JvmField val gradleDaemonVersion: String = "",
        @JvmField val gradleDaemonJar: String = "",
    )
}

internal fun spotlessDaemonInitScript(
    daemonVersion: String,
    daemonJar: String,
): String {
    val script = @Suppress("SpellCheckingInspection")
    $$"""
        gradle.allprojects { proj ->
            proj.buildscript {
                repositories {
                    gradlePluginPortal()
                }
                dependencies {
                    def daemonJar = $${daemonJar.toGroovyStringLiteral()}
                    def daemonVersion = $${daemonVersion.toGroovyStringLiteral()}
                    if (daemonJar) {
                        classpath files(daemonJar)
                    } else {
                        def resolved = daemonVersion ? daemonVersion : '0.5.4'
                        classpath "dev.ghostflyby.spotless.daemon:dev.ghostflyby.spotless.daemon.gradle.plugin:$resolved"
                    }
                }
            }

            proj.pluginManager.withPlugin("com.diffplug.spotless") {
                proj.apply plugin: "dev.ghostflyby.spotless.daemon"
            }
        }
    """.trimIndent()
    return script
}
