/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mill.sdk

import dev.ghostflyby.mill.MillImportDebugLogger
import dev.ghostflyby.mill.command.MillCommandLineUtil
import dev.ghostflyby.mill.project.MillDiscoveredModule
import dev.ghostflyby.mill.settings.MillExecutionSettings

internal object MillModuleJdkResolver {
    fun resolve(
        module: MillDiscoveredModule,
        settings: MillExecutionSettings?,
    ): String? {
        val jdkHomePath = resolveJavaHome(
            module = module,
            settings = settings,
        )?.let(MillModuleJdkSupport::normalizeJdkHomePath)

        MillImportDebugLogger.info(
            "Module `${module.targetPrefix}` resolved javaHome=${jdkHomePath ?: "<project-sdk>"}",
        )
        return jdkHomePath
    }

    private fun resolveJavaHome(
        module: MillDiscoveredModule,
        settings: MillExecutionSettings?,
    ): String? {
        val result = MillCommandLineUtil.readJavaHome(
            projectRoot = module.projectRoot,
            settings = settings,
            targetPrefix = module.queryTarget("java"),
        )
        if (!result.command.isSuccess) {
            MillImportDebugLogger.warn(
                "Module `${module.targetPrefix}` JDK resolution failed exitCode=${result.command.exitCode} details=${
                    MillImportDebugLogger.trim(result.command.failureDetails)
                }",
            )
        }
        return result.value
    }
}
