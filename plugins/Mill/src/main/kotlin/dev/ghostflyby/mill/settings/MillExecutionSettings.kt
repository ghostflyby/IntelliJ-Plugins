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

package dev.ghostflyby.mill.settings

import com.intellij.openapi.externalSystem.model.settings.ExternalSystemExecutionSettings

internal class MillExecutionSettings : ExternalSystemExecutionSettings {
    var millExecutableSource: MillExecutableSource = MillExecutableSource.PROJECT_DEFAULT_SCRIPT
    var millExecutablePath: String = ""
    var useMillMetadataDuringImport: Boolean = true
    var createPerModuleTaskNodes: Boolean = true

    constructor() : super()

    constructor(other: MillExecutionSettings) : super(other) {
        millExecutableSource = other.millExecutableSource
        millExecutablePath = other.millExecutablePath
        useMillMetadataDuringImport = other.useMillMetadataDuringImport
        createPerModuleTaskNodes = other.createPerModuleTaskNodes
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MillExecutionSettings) return false
        if (!super.equals(other)) return false

        return millExecutableSource == other.millExecutableSource &&
                millExecutablePath == other.millExecutablePath &&
                useMillMetadataDuringImport == other.useMillMetadataDuringImport &&
                createPerModuleTaskNodes == other.createPerModuleTaskNodes
    }

    override fun hashCode(): Int {
        var result = 31 * super.hashCode() + millExecutableSource.hashCode()
        result = 31 * result + millExecutablePath.hashCode()
        result = 31 * result + useMillMetadataDuringImport.hashCode()
        result = 31 * result + createPerModuleTaskNodes.hashCode()
        return result
    }
}
