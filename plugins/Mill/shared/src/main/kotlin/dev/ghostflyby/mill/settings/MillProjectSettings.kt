/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mill.settings

import com.intellij.openapi.externalSystem.settings.ExternalProjectSettings

public class MillProjectSettings : ExternalProjectSettings() {
    public var millExecutableSource: MillExecutableSource = MillExecutableSource.PROJECT_DEFAULT_SCRIPT
    public var millExecutablePath: String = ""
    public var useMillMetadataDuringImport: Boolean = true
    public var createPerModuleTaskNodes: Boolean = true

    public override fun clone(): MillProjectSettings {
        val executableConfiguration =
            MillExecutableConfigurationUtil.normalize(millExecutableSource, millExecutablePath)
        return MillProjectSettings().also { receiver ->
            copyTo(receiver)
            receiver.millExecutableSource = executableConfiguration.source
            receiver.millExecutablePath = executableConfiguration.manualPath
            receiver.useMillMetadataDuringImport = useMillMetadataDuringImport
            receiver.createPerModuleTaskNodes = createPerModuleTaskNodes
        }
    }
}
