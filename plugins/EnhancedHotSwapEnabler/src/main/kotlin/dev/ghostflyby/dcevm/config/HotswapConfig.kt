/*
 * Copyright (c) 2025-2026 ghostflyby
 * SPDX-FileCopyrightText: 2025-2026 ghostflyby
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

package dev.ghostflyby.dcevm.config

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolder

internal interface HotswapConfig {
    var inherit: Boolean
    var enable: Boolean
    var enableHotswapAgent: Boolean
    fun setFrom(other: HotswapConfig): HotswapConfig {
        enable = other.enable
        enableHotswapAgent = other.enableHotswapAgent
        inherit = other.inherit
        return this
    }
}

internal data class HotswapConfigState(
    override var inherit: Boolean = true,
    override var enable: Boolean = false,
    override var enableHotswapAgent: Boolean = false,
) : HotswapConfig {
    override fun setFrom(other: HotswapConfig): HotswapConfigState {
        super.setFrom(other)
        return this
    }
}

internal data class ResolvedHotSwapConfig(
    val enable: Boolean,
    val enableHotswapAgent: Boolean,
)

internal fun effectiveHotSwapConfig(
    profile: UserDataHolder?,
    project: Project?,
): ResolvedHotSwapConfig {
    return resolveHotSwapConfig(sequence {
        yield(profile?.hotswapState)
        yield(project?.service<ProjectUserSettings>())
        yield(project?.service<ProjectSharedSettings>())
        yield(service<AppSettings>())
    }
        .filterNotNull())
}

internal fun resolveHotSwapConfig(configs: Sequence<HotswapConfig>): ResolvedHotSwapConfig {
    return configs
        .filterNot { it.inherit }
        .map {
            ResolvedHotSwapConfig(
                enable = it.enable,
                enableHotswapAgent = it.enableHotswapAgent,
            )
        }
        .first()
}

internal const val configKey = "HotswapConfig"
