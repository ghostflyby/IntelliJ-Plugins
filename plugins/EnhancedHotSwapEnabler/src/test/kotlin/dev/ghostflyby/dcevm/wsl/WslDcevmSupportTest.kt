/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.dcevm.wsl

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.testFramework.junit5.TestApplication
import dev.ghostflyby.dcevm.DCEVMSupport
import dev.ghostflyby.dcevm.getDcevmSupport
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * End-to-end regression: runs the real `bin/java -XX:+PrintFlagsFinal -version` check against a JDK
 * living inside the WSL distribution (`\\wsl.localhost\...` UNC home). In 2026.1 GeneralCommandLine
 * routes the ELF execution into the distribution via EEL/IJent instead of hitting Windows
 * CreateProcess error=193.
 */
@Tag("wsl")
@TestApplication
internal class WslDcevmSupportTest {

    private fun optionLines(javaExecutable: String): Sequence<String> =
        GeneralCommandLine(
            javaExecutable,
            "-XX:+PrintFlagsFinal",
            "-version",
        ).createProcess().inputStream.bufferedReader().use { reader ->
            reader.readLines().asSequence()
        }

    @Test
    fun `jdk with enabled dcevm flag resolves auto`() {
        WslTestEnvironment.assumeAvailable()
        val jdkHome = WslTestEnvironment.newIsolatedDir("ijpl-wsl-auto-")
        WslTestEnvironment.createFakeJdk(
            jdkHome,
            " bool AllowEnhancedClassRedefinition = true {product}",
        )
        val support = getDcevmSupport(jdkHome) { javaExecutable -> optionLines(javaExecutable) }
        Assertions.assertEquals(DCEVMSupport.Auto, support)
    }

    @Test
    fun `jdk with disabled dcevm flag requires args`() {
        WslTestEnvironment.assumeAvailable()
        val jdkHome = WslTestEnvironment.newIsolatedDir("ijpl-wsl-req-")
        WslTestEnvironment.createFakeJdk(
            jdkHome,
            " bool AllowEnhancedClassRedefinition = false {product}",
        )
        val support = getDcevmSupport(jdkHome) { javaExecutable -> optionLines(javaExecutable) }
        Assertions.assertEquals(DCEVMSupport.RequiresArg, support)
    }

    @Test
    fun `jdk without dcevm flag resolves none`() {
        WslTestEnvironment.assumeAvailable()
        val jdkHome = WslTestEnvironment.newIsolatedDir("ijpl-wsl-none-")
        WslTestEnvironment.createFakeJdk(
            jdkHome,
            " bool UseCompressedOops = true {product}",
        )
        val support = getDcevmSupport(jdkHome) { javaExecutable -> optionLines(javaExecutable) }
        Assertions.assertEquals(DCEVMSupport.None, support)
    }

    @Test
    fun `alt-jvm layout resolves altJvm without process execution`() {
        WslTestEnvironment.assumeAvailable()
        val jdkHome = WslTestEnvironment.newIsolatedDir("ijpl-wsl-alt-")
        WslTestEnvironment.createAltJvmLayout(jdkHome)
        val support = getDcevmSupport(jdkHome) { error("alt-jvm detection must not execute java") }
        Assertions.assertEquals(DCEVMSupport.AltJvm, support)
    }
}
