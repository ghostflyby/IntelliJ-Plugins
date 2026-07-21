/*
 * Copyright (c) 2025-2026 ghostflyby
 * SPDX-FileCopyrightText: 2025-2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.spotless.gradle

import com.intellij.openapi.components.*
import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.Key
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.model.project.ExternalEntityData
import com.intellij.openapi.externalSystem.model.project.ModuleData
import com.intellij.openapi.externalSystem.model.project.ProjectData
import com.intellij.openapi.externalSystem.service.project.IdeModifiableModelsProvider
import com.intellij.openapi.externalSystem.service.project.manage.AbstractProjectDataService
import com.intellij.openapi.util.NlsSafe
import dev.ghostflyby.spotless.api.SpotlessDaemonProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import org.gradle.api.Project
import org.gradle.tooling.model.idea.IdeaModule
import org.jetbrains.plugins.gradle.service.execution.toGroovyStringLiteral
import org.jetbrains.plugins.gradle.service.project.AbstractProjectResolverExtension
import org.jetbrains.plugins.gradle.service.project.GradleProjectResolverExtension
import org.jetbrains.plugins.gradle.settings.GradleSettings
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
            ideModule.createChild(
                SpotlessGradleStateData.KEY,
                SpotlessGradleStateData(
                    gradleModule.gradleProject.projectDirectory.toPath().absolute(),
                    myModel.spotless,
                ),
            )
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
        val settings = project.service<SpotlessGradleSettings>()
        settings.updateFrom(toImport)
    }
}

private data class SpotlessGradleProviderState(
    override val externalProjects: List<Path>,
    val gradleDaemonVersion: String,
    val gradleDaemonJar: String,
    val syncGeneration: Long,
) : SpotlessDaemonProvider.State

@Service(Service.Level.PROJECT)
@State(
    name = "SpotlessGradleIntegration",
    storages = [Storage(StoragePathMacros.CACHE_FILE, roamingType = RoamingType.DISABLED)],
)
internal class SpotlessGradleSettings
    (private val project: com.intellij.openapi.project.Project) :
    SerializablePersistentStateComponent<SpotlessGradleSettings.State>(State()) {
    private val providerStateLock = Any()
    private var syncGeneration = 0L
    private val mutableProviderState: MutableStateFlow<SpotlessGradleProviderState> by lazy {
        MutableStateFlow(createProviderState())
    }

    val providerState: StateFlow<SpotlessDaemonProvider.State>
        get() = mutableProviderState

    fun updateFrom(nodes: Collection<DataNode<SpotlessGradleStateData>>) {
        updateStateAndPublish(force = true) { state ->
            state.copy(
                paths = nodes.asSequence()
                    .filter { it.data.spotless }
                    .map { it.data.projectDirectory.absolutePathString() }
                    .toSet(),
            )
        }
        nodes.forEach { it.clear(true) }
    }

    var gradleDaemonVersion: @NlsSafe String
        get() = state.gradleDaemonVersion
        set(value) {
            updateStateAndPublish {
                it.copy(gradleDaemonVersion = value)
            }
        }

    var gradleDaemonJar: @NlsSafe String
        get() = state.gradleDaemonJar
        set(value) {
            updateStateAndPublish {
                it.copy(gradleDaemonJar = value)
            }
        }

    private fun updateStateAndPublish(
        force: Boolean = false,
        transform: (State) -> State,
    ) {
        synchronized(providerStateLock) {
            val previous = state
            updateState(transform)
            if (force || state != previous) {
                if (force) {
                    syncGeneration++
                }
                mutableProviderState.value = createProviderState()
            }
        }
    }

    private fun createProviderState(): SpotlessGradleProviderState {
        val current = state
        val externalProjects = GradleSettings.getInstance(project).linkedProjectsSettings
            .asSequence()
            .mapNotNull { it.externalProjectPath }
            .map { Path.of(it).toAbsolutePath().normalize() }
            .filter { it.absolutePathString() in current.paths }
            .distinct()
            .sortedBy(Path::toString)
            .toList()
        return SpotlessGradleProviderState(
            externalProjects = externalProjects,
            gradleDaemonVersion = current.gradleDaemonVersion,
            gradleDaemonJar = current.gradleDaemonJar,
            syncGeneration = syncGeneration,
        )
    }

    @Serializable
    internal data class State(
        val paths: Set<String> = emptySet(),
        val gradleDaemonVersion: String = "",
        val gradleDaemonJar: String = "",
    )
}

internal fun spotlessDaemonInitScript(
    daemonVersion: String,
    daemonJar: String,
): String {
    val script =
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
                        def resolved = daemonVersion ? daemonVersion : '0.7.0'
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
