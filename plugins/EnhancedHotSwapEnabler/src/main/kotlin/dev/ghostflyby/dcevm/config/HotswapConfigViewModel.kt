/*
 * Copyright (c) 2025 ghostflyby <ghostflyby+intellij@outlook.com>
 *
 * This program is free software; you can redistribute it and/or
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

// Yes, I know the intelliJ is basically MVC based, but I need a place to bridge different config systems.
// if only the `Configurable` and `SettingsEditor` were one unified system.
internal class HotswapConfigViewModel(
    inherit: Boolean = true,
    enable: Boolean = false,
    enableHotswapAgent: Boolean = false,
) : HotswapConfig {
    private val graph = PropertyGraph("HotSwapConfigViewModel")
    val inheritProperty = graph.property(inherit)
    val enableProperty = graph.property(enable)
    val enableEditableProperty = graph.property(!inherit).apply {
        dependsOn(inheritProperty) {
            !inheritProperty.get()
        }
    }
    val enableHotswapAgentProperty = graph.property(enableHotswapAgent)
    val enableHotswapAgentEditableProperty = graph.property(!inherit && enable).apply {
        fun reset(): Boolean {
            return !inheritProperty.get() && enableProperty.get()
        }
        dependsOn(enableEditableProperty, ::reset)
        dependsOn(enableProperty, ::reset)
    }

    override var inherit by inheritProperty

    override var enable by enableProperty
    override var enableHotswapAgent by enableHotswapAgentProperty

    /**
     * for top-level configuration, this should be `false`,
     * as Application level settings have no parent to inherit from.
     */
    var inheritEnable = true
}