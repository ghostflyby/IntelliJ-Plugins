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

package dev.ghostflyby.dcevm

import com.intellij.openapi.observable.properties.ObservableMutableProperty
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.util.NlsContexts
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.bindState
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.ThreeStateCheckBox
import javax.swing.JComponent
import kotlin.reflect.KMutableProperty0

internal sealed class DCEVMConfigurable : Configurable, HotSwapConfigMutable {

    abstract val persistent: HotSwapPersistent

    override fun getDisplayName(): @NlsContexts.ConfigurableName String {
        return "DCEVM and Hotswap Agent"
    }

    final override fun isModified(): Boolean {
        return modified
    }

    private var modified = false
    private fun modify(unused: Any?) {
        modified = true
    }

    final override fun reset() {
        this.setFrom(persistent)
    }

    override fun createComponent(): JComponent =
        panel {
            row("Enable DCEVM") {
                threeStateCheckBox("Enable DCEVM").bindStateToBoolean(::enable).onChanged(::modify)
            }
            row("Enable Hotswap Agent") {
                threeStateCheckBox("Enable hotswap agent").bindStateToBoolean(::enableHotswapAgent).onChanged(::modify)
            }
        }

    final override fun apply() {
        persistent.setFrom(this)
    }

    override var enable: Boolean? = null
    override var enableHotswapAgent: Boolean? = null
}

internal class ThreeStataBooleanProperty(
    private val property: KMutableProperty0<Boolean?>,
) : ObservableMutableProperty<ThreeStateCheckBox.State> {
    override fun set(value: ThreeStateCheckBox.State) {
        property.set(
            when (value) {
                ThreeStateCheckBox.State.SELECTED -> true
                ThreeStateCheckBox.State.NOT_SELECTED -> false
                ThreeStateCheckBox.State.DONT_CARE -> null
            }
        )
    }

    override fun get(): ThreeStateCheckBox.State {
        return when (property.get()) {
            true -> ThreeStateCheckBox.State.SELECTED
            false -> ThreeStateCheckBox.State.NOT_SELECTED
            null -> ThreeStateCheckBox.State.DONT_CARE
        }
    }

}

internal fun <T : ThreeStateCheckBox> Cell<T>.bindStateToBoolean(property: KMutableProperty0<Boolean?>): Cell<T> =
    this.bindState(ThreeStataBooleanProperty(property))