/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

@file:Suppress("UnstableApiUsage")
// Mirrors the Experimental target-parameter surface the production injection
// (HotswapAgentJvmArguments) is built on; the test exercises its resolution directly.

package dev.ghostflyby.dcevm.agent

import com.intellij.execution.target.value.TargetValue
import dev.ghostflyby.dcevm.missingHotswapAgentAddOpensJvmArgs
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

internal class HotswapAgentTargetParameterTest {

    private val localAgentJar = "C:\\ide\\sandbox\\plugins\\EnhancedHotSwapEnabler\\lib\\hotswap-agent-1.4.23.jar"

    @Test
    fun `local resolution keeps the host path`() {
        val parameter = hotswapAgentParameter(localAgentJar)

        Assertions.assertEquals("-javaagent:$localAgentJar", parameter.toLocalParameter())
    }

    @Test
    fun `target resolution rewrites the uploaded path`() {
        val parameter = hotswapAgentParameter(localAgentJar)

        parameter.resolvePaths(
            uploadPathsResolver = { TargetValue.fixed("/tmp/ij-agents/hotswap-agent-1.4.23.jar") },
            downloadPathsResolver = { TargetValue.fixed(it.localPath) },
        )

        Assertions.assertEquals(
            "-javaagent:/tmp/ij-agents/hotswap-agent-1.4.23.jar",
            parameter.parameter.targetValue.blockingGet(0),
        )
    }

    @Test
    fun `fixed parameters pass through unchanged`() {
        val parameter = fixedJvmParameter("-XX:+AllowEnhancedClassRedefinition")

        Assertions.assertEquals("-XX:+AllowEnhancedClassRedefinition", parameter.toLocalParameter())
    }

    @Test
    fun `add opens args skip already present targets`() {
        val injected = missingHotswapAgentAddOpensJvmArgs(
            listOf("--add-opens=java.base/java.lang=ALL-UNNAMED", "-Dsome.property=1"),
            isJava9OrHigher = true,
        )

        Assertions.assertFalse(injected.contains("--add-opens=java.base/java.lang=ALL-UNNAMED"))
        Assertions.assertEquals(6, injected.size)
    }

    @Test
    fun `add opens args are skipped below java 9`() {
        val injected = missingHotswapAgentAddOpensJvmArgs(emptyList(), isJava9OrHigher = false)

        Assertions.assertTrue(injected.isEmpty())
    }
}
