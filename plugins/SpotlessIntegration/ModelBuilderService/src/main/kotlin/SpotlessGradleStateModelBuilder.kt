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

import org.gradle.api.Project
import org.jetbrains.plugins.gradle.tooling.ModelBuilderService
import java.io.Serializable

@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "This is internal API and may change without notice, only public for technical reasons.",
)
@SpotlessIntegrationPluginInternalApi
public annotation class SpotlessIntegrationPluginInternalApi

@SpotlessIntegrationPluginInternalApi
public class SpotlessGradleStateModelBuilder : ModelBuilderService {

    override fun canBuild(modelName: String): Boolean = modelName == SpotlessGradleStateModel::class.java.name

    override fun buildAll(modelName: String, project: Project): Any {
        return SpotlessGradleStateModelImpl(
            project.pluginManager.hasPlugin("com.diffplug.spotless"),
        )
    }
}


@SpotlessIntegrationPluginInternalApi
public interface SpotlessGradleStateModel : Serializable {
    public val spotless: Boolean
}

internal data class SpotlessGradleStateModelImpl(
    val spotless: Boolean,
) : Serializable
