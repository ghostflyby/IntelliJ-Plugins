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

package dev.ghostflyby.dcevm.config

import com.intellij.openapi.observable.properties.PropertyGraph
import dev.ghostflyby.dcevm.Bundle

internal class ProjectDCEVMConfigViewModel(
    private val applicationConfig: HotswapConfig,
) {
    private val graph = PropertyGraph("ProjectDCEVMConfigViewModel")

    val sharedState = HotswapConfigViewModel()
    val workspaceState = HotswapConfigViewModel()

    val summarySourceProperty = graph.property(computeSummarySource()).apply {
        dependsOn(workspaceState.inheritProperty, ::computeSummarySource)
        dependsOn(sharedState.inheritProperty, ::computeSummarySource)
    }
    val summaryEnableProperty = graph.property(computeSummaryEnable()).apply {
        dependsOn(workspaceState.inheritProperty, ::computeSummaryEnable)
        dependsOn(workspaceState.enableProperty, ::computeSummaryEnable)
        dependsOn(sharedState.inheritProperty, ::computeSummaryEnable)
        dependsOn(sharedState.enableProperty, ::computeSummaryEnable)
    }
    val summaryEnableHotswapAgentProperty = graph.property(computeSummaryEnableHotswapAgent()).apply {
        dependsOn(workspaceState.inheritProperty, ::computeSummaryEnableHotswapAgent)
        dependsOn(workspaceState.enableHotswapAgentProperty, ::computeSummaryEnableHotswapAgent)
        dependsOn(sharedState.inheritProperty, ::computeSummaryEnableHotswapAgent)
        dependsOn(sharedState.enableHotswapAgentProperty, ::computeSummaryEnableHotswapAgent)
    }

    fun reset(sharedPersistent: HotswapPersistent, workspacePersistent: HotswapPersistent) {
        sharedState.setFrom(sharedPersistent)
        workspaceState.setFrom(workspacePersistent)
    }

    fun apply(sharedPersistent: HotswapPersistent, workspacePersistent: HotswapPersistent) {
        sharedPersistent.setFrom(sharedState)
        workspacePersistent.setFrom(workspaceState)
    }

    fun isModified(sharedPersistent: HotswapPersistent, workspacePersistent: HotswapPersistent): Boolean {
        return hasChanges(sharedPersistent, sharedState) || hasChanges(workspacePersistent, workspaceState)
    }

    private fun computeSummarySource(): String {
        return resolvedConfigSource().first
    }

    private fun computeSummaryEnable(): String {
        return booleanSummary(resolvedConfigSource().second.enable)
    }

    private fun computeSummaryEnableHotswapAgent(): String {
        return booleanSummary(resolvedConfigSource().second.enableHotswapAgent)
    }

    private fun resolvedConfigSource(): Pair<String, HotswapConfig> {
        return sequenceOf(
            Bundle.message("configuration.summary.source.workspace") to workspaceState,
            Bundle.message("configuration.summary.source.project") to sharedState,
            Bundle.message("configuration.summary.source.application") to applicationConfig,
        ).first { (_, config) -> !config.inherit }
    }

    private fun booleanSummary(value: Boolean): String {
        return Bundle.message(
            if (value) {
                "configuration.summary.enabled"
            } else {
                "configuration.summary.disabled"
            },
        )
    }

    private fun hasChanges(persistent: HotswapPersistent, state: HotswapConfig): Boolean {
        return persistent.enable != state.enable ||
                persistent.enableHotswapAgent != state.enableHotswapAgent ||
                persistent.inherit != state.inherit
    }
}
