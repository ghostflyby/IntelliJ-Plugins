/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
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
    public const val notificationGroupId: String = "dev.ghostflyby.mill.import"
    public const val wrapperScriptName: String = "mill"
    public const val wrapperBatchName: String = "mill.bat"
    public const val rootModulePrefix: String = "<root>"
    public const val moduleDiscoveryQuery: String = "__"
    public const val moduleFilesDirectory: String = ".idea/modules"
    public const val buildScriptModuleName: String = "mill-build_"
    public const val buildScriptFileName: String = "build.mill"
    public const val buildScriptOutputDirectory: String = "out/mill-build"
    public const val buildScriptGeneratedSourcesFileName: String = "generatedScriptSources.json"
    public const val buildScriptClasspathFileName: String = "compileClasspath.json"

    @JvmField
    public val projectFileNames: Set<String> = linkedSetOf(buildScriptFileName, "build.mill.yaml")

    @JvmField
    public val configFileNames: Set<String> = linkedSetOf(
        *projectFileNames.toTypedArray(),
        versionFileName,
    )
}
