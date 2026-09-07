/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

@file:Suppress("UnstableApiUsage")
// JavaTargetDependentParameters / JavaTargetParameter / TargetPaths are Experimental APIs in 2026.1:
// the target-agnostic VM parameter injection surface (host path for local runs, automatic upload +
// path rewrite for WSL/container targets). Track platform stabilization here.

package dev.ghostflyby.dcevm.agent

import com.intellij.execution.target.java.JavaTargetParameter
import com.intellij.execution.target.java.TargetPaths

internal const val JVM_AGENT_OPTION: String = "-javaagent:"

internal const val JDWP_AGENTLIB_OPTION: String = "-agentlib:jdwp"

/**
 * Builds a target-agnostic `-javaagent:` parameter: it resolves to the host path for local runs,
 * while for WSL/container targets the platform (`JdkCommandLineSetup.appendVmParameters`) uploads
 * the jar into the target and rewrites the path to its target-side form.
 */
internal fun hotswapAgentParameter(agentJarPath: String): JavaTargetParameter =
    JavaTargetParameter.Builder(TargetPaths.unordered(uploadPaths = setOf(agentJarPath)))
        .fixed(JVM_AGENT_OPTION)
        .resolved(agentJarPath)
        .build()

internal fun fixedJvmParameter(value: String): JavaTargetParameter = JavaTargetParameter.fixed(value)
