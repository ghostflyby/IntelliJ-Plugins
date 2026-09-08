/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.dcevm

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * OS-agnostic detection tests for [getDcevmSupport]: the classification runs against local
 * temporary directories and stubbed `PrintFlagsFinal` output, without executing any process.
 * The end-to-end WSL transport variant lives in `dev.ghostflyby.dcevm.wsl.WslDcevmSupportTest`.
 */
internal class DcevmSupportDetectionTest {

    private fun newJdkHome(): Path = Files.createTempDirectory("ijpl-dcevm-detect-")

    @Test
    fun `alt-jvm directory layout resolves altJvm without process execution`() {
        val jdkHome = newJdkHome()
        Files.createDirectories(jdkHome.resolve("lib").resolve("dcevm"))

        val support = getDcevmSupport(jdkHome) { error("alt-jvm detection must not execute java") }

        Assertions.assertEquals(DCEVMSupport.AltJvm, support)
    }

    @Test
    fun `flag line with true resolves auto`() {
        val jdkHome = newJdkHome()

        val support = getDcevmSupport(jdkHome) { _ ->
            sequenceOf(" bool AllowEnhancedClassRedefinition = true {product}")
        }

        Assertions.assertEquals(DCEVMSupport.Auto, support)
    }

    @Test
    fun `flag line with false resolves requiresArgs`() {
        val jdkHome = newJdkHome()

        val support = getDcevmSupport(jdkHome) { _ ->
            sequenceOf(" bool AllowEnhancedClassRedefinition = false {product}")
        }

        Assertions.assertEquals(DCEVMSupport.RequiresArg, support)
    }

    @Test
    fun `absent dcevm flag line resolves none`() {
        val jdkHome = newJdkHome()

        val support = getDcevmSupport(jdkHome) { _ ->
            sequenceOf(" bool UseCompressedOops = true {product}")
        }

        Assertions.assertEquals(DCEVMSupport.None, support)
    }
}
