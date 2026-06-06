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

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.Key
import com.intellij.openapi.externalSystem.model.project.ModuleData
import com.intellij.openapi.externalSystem.model.project.ProjectData
import com.intellij.openapi.externalSystem.service.project.IdeModifiableModelsProvider
import com.intellij.openapi.externalSystem.service.project.manage.AbstractProjectDataService
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import org.jetbrains.plugins.scala.project.ReplClasspath
import org.jetbrains.plugins.scala.project.`ReplClasspath$`
import org.jetbrains.plugins.scala.project.external.`ScalaSdkUtils$`
import scala.Option
import scala.collection.immutable.Seq
import scala.jdk.javaapi.CollectionConverters
import java.nio.file.Path

internal class MillScalaSdkDataService : AbstractProjectDataService<MillScalaSdkData, Module>() {
    override fun getTargetDataKey(): Key<MillScalaSdkData> = MillScalaSdkData.key

    override fun importData(
        toImport: Collection<DataNode<MillScalaSdkData>>,
        projectData: ProjectData?,
        project: Project,
        modelsProvider: IdeModifiableModelsProvider,
    ) {
        for (dataNode in toImport) {
            val moduleData = dataNode.parent?.data as? ModuleData ?: continue
            val ideModule = modelsProvider.findIdeModule(moduleData) ?: continue

            try {
                val data = dataNode.data
                LOG.info(
                    "[Mill import] Configuring Scala SDK for module ${ideModule.name} " +
                            "version=${data.scalaVersion} " +
                            "scalacJars=${data.scalacClasspath.size} " +
                            "scaladocJars=${data.scaladocClasspath.size} " +
                            "replJars=${data.replClasspath.size}",
                )
                val replClasspath: ReplClasspath = `ReplClasspath$`.`MODULE$`.fromPaths(toScalaSeq(data.replClasspath))
                `ScalaSdkUtils$`.`MODULE$`.configureScalaSdk(
                    ideModule,
                    data.scalaVersion,
                    toScalaSeq(data.scalacClasspath),
                    toScalaSeq(data.scaladocClasspath),
                    Option.empty(),
                    replClasspath,
                    MillConstants.scalaSdkPrefix,
                    modelsProvider,
                )
                LOG.info("[Mill import] Scala SDK configured for module ${ideModule.name}")
            } catch (throwable: Throwable) {
                LOG.warn("Failed to configure Mill Scala SDK for module ${ideModule.name}", throwable)
            }
        }
    }

    private fun toScalaSeq(values: List<String>): Seq<Path> {
        val paths = ArrayList<Path>(values.size)
        values.forEach { value ->
            paths.add(Path.of(value))
        }
        return CollectionConverters.asScala(paths).toSeq()
    }

    companion object {
        private val LOG: Logger = Logger.getInstance(MillScalaSdkDataService::class.java)
    }
}
