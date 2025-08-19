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

import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import dev.ghostflyby.dcevm.Bundle

/**
 * Creates a UI panel for configuring hot swap settings.
 * @param model The view model to bind
 * @return A panel with the hot swap configuration options
 */
internal fun hotswapConfigView(
    model: HotswapConfigViewModel,
) =
    panel {
        row {
            checkBox(Bundle.message("checkbox.settings.inherit.parent")).bindSelected(model.inheritProperty)
        }.visible(model.inheritEnable)
        (if (model.inheritEnable) ::indent else {
            {
                this.it()
            }
        }) {
            row {
                checkBox(Bundle.message("checkbox.enable")).bindSelected(model.enableProperty)
                    .enabledIf(model.enableEditableProperty)
            }
            row {
                checkBox(Bundle.message("checkbox.hotswapAgent"))
                    .bindSelected(model.enableHotswapAgentProperty)
                    .enabledIf(model.enableHotswapAgentEditableProperty)
            }
        }
    }
