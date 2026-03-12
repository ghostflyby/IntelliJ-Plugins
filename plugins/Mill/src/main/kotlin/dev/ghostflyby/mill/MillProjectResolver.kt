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

import com.intellij.execution.process.ProcessOutputType
import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.ProjectKeys
import com.intellij.openapi.externalSystem.model.project.ProjectData
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import com.intellij.openapi.externalSystem.service.project.ExternalSystemProjectResolver
import dev.ghostflyby.mill.project.MillContentRootResolver
import dev.ghostflyby.mill.project.MillDiscoveredModule
import dev.ghostflyby.mill.project.MillExternalLibraryResolver
import dev.ghostflyby.mill.project.MillModuleDependencyResolver
import dev.ghostflyby.mill.project.MillModuleDiscovery
import dev.ghostflyby.mill.project.MillModuleOutputResolver
import dev.ghostflyby.mill.project.MillProjectResolverSupport
import dev.ghostflyby.mill.script.MillBuildScriptModuleResolver
import dev.ghostflyby.mill.sdk.MillModuleJdkHomeProperty
import dev.ghostflyby.mill.sdk.MillModuleJdkResolver
import dev.ghostflyby.mill.sdk.MillScalaSdkResolver
import java.nio.file.Path

internal class MillProjectResolver : ExternalSystemProjectResolver<MillExecutionSettings> {
    override fun resolveProjectInfo(
        id: ExternalSystemTaskId,
        projectPath: String,
        isPreviewMode: Boolean,
        settings: MillExecutionSettings?,
        listener: ExternalSystemTaskNotificationListener,
    ): DataNode<ProjectData> {
        val progressReporter = MillImportProgressReporter(id, listener)
        progressReporter.started("Importing Mill project")
        MillImportDebugLogger.info(
            "Starting resolver for projectPath=$projectPath preview=$isPreviewMode " +
                "metadataImport=${settings?.useMillMetadataDuringImport} perModuleTasks=${settings?.createPerModuleTaskNodes} " +
                "millExecutable=${settings?.millExecutablePath.orEmpty().ifBlank { MillConstants.defaultExecutable }}",
        )
        return try {
            progressReporter.progress(15, "Resolving Mill project root")
            val root = MillProjectResolverSupport.findProjectRoot(projectPath)
            MillImportDebugLogger.info("Resolved Mill root to $root")

            progressReporter.progress(40, "Building Mill project model")
            val projectData = MillProjectResolverSupport.buildProjectData(root)
            val projectNode = DataNode(ProjectKeys.PROJECT, projectData, null)
            MillModuleJdkResolver.resolve(
                module = MillDiscoveredModule(
                    displayName = projectData.externalName,
                    targetPrefix = MillConstants.rootModulePrefix,
                    projectRoot = root,
                    directory = root,
                ),
                settings = settings,
            )?.let { jdkHomePath ->
                projectNode.createChild(MillProjectJdkData.key, MillProjectJdkData(jdkHomePath))
            }

            progressReporter.progress(55, "Discovering Mill modules")
            val resolvedTargets = MillModuleDiscovery.resolveTargets(root, settings, id, listener)
            MillImportDebugLogger.info(
                "Resolved ${resolvedTargets.size} target(s): ${MillImportDebugLogger.sample(resolvedTargets)}",
            )
            val discoveredModules = MillModuleDiscovery.discoverModulesFromTargets(root, projectData.externalName, resolvedTargets)
                .ifEmpty { listOf(MillDiscoveredModule(projectData.externalName, MillConstants.rootModulePrefix, root, root)) }
            MillImportDebugLogger.warn(
                "Discovered ${discoveredModules.size} module(s): ${
                    MillImportDebugLogger.sample(discoveredModules.map { "${it.targetPrefix} -> ${it.directory}" })
                }",
            )

            progressReporter.progress(70, "Creating module and content roots")
            val moduleNodes = discoveredModules.map { module ->
                val moduleData = MillProjectResolverSupport.buildModuleData(
                    projectRoot = root,
                    moduleDir = module.directory,
                    moduleName = module.displayName,
                )
                MillModuleOutputResolver.resolveModuleOutputs(module, settings, id, listener)?.let { outputs ->
                    MillModuleOutputResolver.applyModuleOutputs(moduleData, module, outputs)
                }
                val moduleNode = projectNode.createChild(ProjectKeys.MODULE, moduleData)
                moduleNode.createChild(
                    ProjectKeys.CONTENT_ROOT,
                    MillContentRootResolver.buildContentRoot(module, settings, id, listener),
                )
                MillImportDebugLogger.info("Created module node `${module.targetPrefix}` for ${module.directory}")
                module to moduleNode
            }

            val moduleNodesByTargetPrefix = moduleNodes.associateBy({ it.first.targetPrefix }, { it.second })
            moduleNodes.forEach { (module, node) ->
                val dependencyPrefixes = linkedSetOf<String>()
                val productionModuleNode = module.productionModulePrefix?.let(moduleNodesByTargetPrefix::get)
                if (productionModuleNode != null) {
                    node.data.productionModuleId = productionModuleNode.data.id
                    dependencyPrefixes += module.productionModulePrefix
                }
                dependencyPrefixes += MillModuleDependencyResolver.resolveDependencyPrefixes(
                    module = module,
                    discoveredModules = discoveredModules,
                    settings = settings,
                    taskId = id,
                    listener = listener,
                )
                dependencyPrefixes.forEach { dependencyPrefix ->
                    val dependencyNode = moduleNodesByTargetPrefix[dependencyPrefix] ?: return@forEach
                    node.createChild(
                        ProjectKeys.MODULE_DEPENDENCY,
                        MillProjectResolverSupport.buildModuleDependency(node.data, dependencyNode.data),
                    )
                }
                MillImportDebugLogger.info(
                    "Module `${module.targetPrefix}` direct module deps: ${MillImportDebugLogger.sample(dependencyPrefixes)}",
                )
            }

            val buildScriptImport = MillBuildScriptModuleResolver.resolve(root)
            val buildScriptLibraryCount = buildScriptImport?.libraryPaths?.size ?: 0
            val buildScriptModuleNode = buildScriptImport?.let {
                val moduleData = MillProjectResolverSupport.buildModuleData(
                    projectRoot = root,
                    moduleDir = it.module.directory,
                    moduleName = it.module.displayName,
                )
                val moduleNode = projectNode.createChild(ProjectKeys.MODULE, moduleData)
                moduleNode.createChild(ProjectKeys.CONTENT_ROOT, it.contentRoot)
                it.libraryPaths.forEach { path ->
                    moduleNode.createChild(
                        ProjectKeys.LIBRARY_DEPENDENCY,
                        MillProjectResolverSupport.buildLibraryDependency(moduleData, path),
                    )
                }
                it.scalaSdkData?.let { scalaSdkData ->
                    moduleNode.createChild(MillScalaSdkData.key, scalaSdkData)
                }
                it.jdkHomePath?.let { jdkHomePath ->
                    moduleData.setProperty(MillModuleJdkHomeProperty, jdkHomePath)
                }
                MillImportDebugLogger.info(
                    "Created build script module `${it.module.targetPrefix}` " +
                        "with ${it.libraryPaths.size} librar" +
                        if (it.libraryPaths.size == 1) "y" else "ies",
                )
                moduleNode
            }

            progressReporter.progress(82, "Resolving Mill external libraries")
            var libraryCount = 0
            moduleNodes.forEach { (module, node) ->
                val libraryPaths = MillExternalLibraryResolver.resolveBinaryLibraryPaths(
                    module = module,
                    settings = settings,
                    taskId = id,
                    listener = listener,
                )
                libraryCount += libraryPaths.size
                MillImportDebugLogger.info(
                    "Module `${module.targetPrefix}` resolved ${libraryPaths.size} external librar" +
                        if (libraryPaths.size == 1) "y" else "ies" +
                        ": ${MillImportDebugLogger.sample(libraryPaths.map(Path::toString))}",
                )
                libraryPaths.forEach { path ->
                    node.createChild(
                        ProjectKeys.LIBRARY_DEPENDENCY,
                        MillProjectResolverSupport.buildLibraryDependency(node.data, path),
                    )
                }
                MillScalaSdkResolver.resolve(
                    module = module,
                    settings = settings,
                    taskId = id,
                    listener = listener,
                )?.let { scalaSdkData ->
                    MillImportDebugLogger.info(
                        "Module `${module.targetPrefix}` resolved Scala SDK version=${scalaSdkData.scalaVersion} " +
                            "scalacJars=${scalaSdkData.scalacClasspath.size} scaladocJars=${scalaSdkData.scaladocClasspath.size} " +
                            "replJars=${scalaSdkData.replClasspath.size}",
                    )
                    node.createChild(MillScalaSdkData.key, scalaSdkData)
                } ?: MillImportDebugLogger.info("Module `${module.targetPrefix}` has no Scala SDK metadata")
                MillModuleJdkResolver.resolve(
                    module = module,
                    settings = settings,
                )?.let { jdkHomePath ->
                    node.data.setProperty(MillModuleJdkHomeProperty, jdkHomePath)
                }
            }
            if (buildScriptModuleNode != null) {
                libraryCount += buildScriptLibraryCount
            }

            progressReporter.progress(90, "Collecting Mill tasks")
            val tasks = if (settings?.createPerModuleTaskNodes == false) {
                MillProjectResolverSupport.createTaskData(root)
            } else {
                MillProjectResolverSupport.createTaskData(root, discoveredModules, resolvedTargets)
            }
            MillImportDebugLogger.info("Created ${tasks.size} task node(s)")
            val tasksNode = projectNode.createChild(
                MillTasksData.key,
                MillTasksData(projectData.externalName),
            )
            tasks.forEach { task ->
                tasksNode.createChild(ProjectKeys.TASK, task)
            }

            progressReporter.progress(100, "Finalizing Mill import")
            listener.onTaskOutput(
                id,
                buildString {
                    append(
                        "Resolved a basic Mill project model for ${
                            root.fileName?.toString().orEmpty().ifBlank { "Mill" }
                        }.",
                    )
                    append(" Imported ${moduleNodes.size + if (buildScriptModuleNode == null) 0 else 1} modules.")
                    if (libraryCount > 0) append(" Imported $libraryCount external libraries.")
                    append('\n')
                },
                ProcessOutputType.STDOUT,
            )
            MillImportDebugLogger.info(
                "Resolver finished successfully with modules=${moduleNodes.size + if (buildScriptModuleNode == null) 0 else 1} " +
                    "libraries=$libraryCount tasks=${tasks.size}",
            )
            progressReporter.finished("Mill import finished")
            projectNode
        } catch (error: Throwable) {
            MillImportDebugLogger.warn("Resolver failed for projectPath=$projectPath", error)
            progressReporter.failed("Mill import failed", error)
            throw error
        }
    }

    override fun cancelTask(id: ExternalSystemTaskId, listener: ExternalSystemTaskNotificationListener): Boolean = false
}
