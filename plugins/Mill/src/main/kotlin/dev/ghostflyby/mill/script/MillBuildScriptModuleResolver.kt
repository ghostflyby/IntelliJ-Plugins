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

package dev.ghostflyby.mill.script

import com.intellij.openapi.externalSystem.model.project.ContentRootData
import dev.ghostflyby.mill.MillConstants
import dev.ghostflyby.mill.MillScalaSdkData
import dev.ghostflyby.mill.project.MillDiscoveredModule
import dev.ghostflyby.mill.project.MillProjectResolverSupport
import java.nio.file.Files
import java.nio.file.Path

internal data class MillBuildScriptImportModel(
    val module: MillDiscoveredModule,
    val contentRoot: ContentRootData,
    val libraryPaths: List<Path>,
    val scalaSdkData: MillScalaSdkData?,
    val jdkHomePath: String?,
)

internal object MillBuildScriptModuleResolver {
    fun resolve(
        projectRoot: Path,
    ): MillBuildScriptImportModel? {
        if (!Files.isRegularFile(MillBuildScriptSupport.buildScriptFile(projectRoot))) {
            return null
        }

        val module = MillDiscoveredModule(
            displayName = MillConstants.buildScriptModuleName,
            targetPrefix = MillConstants.rootModulePrefix,
            projectRoot = projectRoot,
            directory = projectRoot,
        )
        val model = MillBuildScriptSupport.loadModel(projectRoot)
        return MillBuildScriptImportModel(
            module = module,
            contentRoot = MillProjectResolverSupport.buildBuildScriptContentRoot(projectRoot, model?.sourceRoots.orEmpty()),
            libraryPaths = model?.resolveBinaryClasspath.orEmpty(),
            scalaSdkData = model?.toScalaSdkData(),
            jdkHomePath = model?.javaHomePath,
        )
    }

    private fun MillBuildScriptModel.toScalaSdkData(): MillScalaSdkData? {
        val scalaVersion = scalaVersion ?: return null
        if (scalaCompilerClasspath.isEmpty()) {
            return null
        }
        return MillScalaSdkData(
            scalaVersion = scalaVersion,
            scalacClasspath = scalaCompilerClasspath.map(Path::toString),
            scaladocClasspath = emptyList(),
            replClasspath = emptyList(),
        )
    }
}
