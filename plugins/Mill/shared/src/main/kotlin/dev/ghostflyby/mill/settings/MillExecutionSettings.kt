/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mill.settings

import com.intellij.openapi.externalSystem.model.settings.ExternalSystemExecutionSettings

public class MillExecutionSettings : ExternalSystemExecutionSettings {
    public var millExecutableSource: MillExecutableSource = MillExecutableSource.PROJECT_DEFAULT_SCRIPT
    public var millExecutablePath: String = ""
    public var useMillMetadataDuringImport: Boolean = true
    public var createPerModuleTaskNodes: Boolean = true
    public var target: MillExecutionTarget = MillExecutionTarget.Local

    public constructor() : super()

    public constructor(other: MillExecutionSettings) : super(other) {
        millExecutableSource = other.millExecutableSource
        millExecutablePath = other.millExecutablePath
        useMillMetadataDuringImport = other.useMillMetadataDuringImport
        createPerModuleTaskNodes = other.createPerModuleTaskNodes
        target = other.target
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MillExecutionSettings) return false
        if (!super.equals(other)) return false

        return millExecutableSource == other.millExecutableSource &&
                millExecutablePath == other.millExecutablePath &&
                useMillMetadataDuringImport == other.useMillMetadataDuringImport &&
                createPerModuleTaskNodes == other.createPerModuleTaskNodes &&
                target == other.target
    }

    override fun hashCode(): Int {
        var result = 31 * super.hashCode() + millExecutableSource.hashCode()
        result = 31 * result + millExecutablePath.hashCode()
        result = 31 * result + useMillMetadataDuringImport.hashCode()
        result = 31 * result + createPerModuleTaskNodes.hashCode()
        result = 31 * result + target.hashCode()
        return result
    }
}
