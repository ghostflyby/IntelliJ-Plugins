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

import com.intellij.openapi.externalSystem.model.ProjectSystemId

public object MillConstants {
    @JvmField
    public val systemId: ProjectSystemId = ProjectSystemId("MILL", "Mill")

    public const val defaultExecutable: String = "mill"
    public const val moduleTypeId: String = "JAVA_MODULE"
    public const val settingsFileName: String = "mill.xml"
    public const val versionFileName: String = ".mill-version"
    public const val scalaSdkPrefix: String = "Mill"
    public const val notificationGroupId: String = "Mill Import"
    public const val wrapperScriptName: String = "mill"
    public const val wrapperBatchName: String = "mill.bat"
    public const val rootModulePrefix: String = "<root>"
    public const val moduleDiscoveryQuery: String = "__"
    public const val moduleFilesDirectory: String = ".idea/modules"

    @JvmField
    public val projectFileNames: Set<String> = linkedSetOf("build.mill", "build.mill.yaml")

    @JvmField
    public val configFileNames: Set<String> = linkedSetOf(
        *projectFileNames.toTypedArray(),
        versionFileName,
    )
}
