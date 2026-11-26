/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mill

import com.intellij.openapi.externalSystem.model.ProjectSystemId

public object MillConstants {
    @JvmField
    public val SYSTEM_ID: ProjectSystemId = ProjectSystemId("MILL", Bundle.message("system.display.name"))

    public const val DEFAULT_EXECUTABLE: String = "mill"
    public const val MODULE_TYPE_ID: String = "JAVA_MODULE"
    public const val SETTINGS_FILE_NAME: String = "mill.xml"
    public const val VERSION_FILE_NAME: String = ".mill-version"
    public const val SCALA_SDK_PREFIX: String = "Mill"
    public const val NOTIFICATION_GROUP_ID: String = "dev.ghostflyby.mill.import"
    public const val WRAPPER_SCRIPT_NAME: String = "mill"
    public const val WRAPPER_BATCH_NAME: String = "mill.bat"
    public const val ROOT_MODULE_PREFIX: String = "<root>"
    public const val MODULE_DISCOVERY_QUERY: String = "__"
    public const val MODULE_FILES_DIRECTORY: String = ".idea/modules"
    public const val BUILD_SCRIPT_MODULE_NAME: String = "mill-build_"
    public const val BUILD_SCRIPT_FILE_NAME: String = "build.mill"
    public const val BUILD_SCRIPT_OUTPUT_DIRECTORY: String = "out/mill-build"
    public const val BUILD_SCRIPT_GENERATED_SOURCES_FILE_NAME: String = "generatedScriptSources.json"
    public const val BUILD_SCRIPT_CLASSPATH_FILE_NAME: String = "compileClasspath.json"

    @JvmField
    public val PROJECT_FILE_NAMES: Set<String> = linkedSetOf(BUILD_SCRIPT_FILE_NAME, "build.mill.yaml")

    @JvmField
    public val CONFIG_FILE_NAMES: Set<String> = linkedSetOf(
        *PROJECT_FILE_NAMES.toTypedArray(),
        VERSION_FILE_NAME,
    )
}
