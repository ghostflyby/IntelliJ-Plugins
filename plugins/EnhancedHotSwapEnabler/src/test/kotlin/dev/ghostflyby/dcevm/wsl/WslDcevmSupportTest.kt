/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.dcevm.wsl

import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import dev.ghostflyby.dcevm.DCEVMSupport
import dev.ghostflyby.dcevm.eel.detectDcevmSupport
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

/**
 * End-to-end WSL transport variant of `DcevmSupportDetectionTest`: runs the production
 * `detectDcevmSupport` routing (EEL exec for non-local descriptors) against a JDK living inside
 * the WSL distribution (`\\wsl.localhost\...` UNC home). The WSL branch must execute the ELF
 * inside the distribution instead of hitting Windows CreateProcess error=193.
 *
 * Temporarily disabled because the test classpath now carries the IDE's bundled plugins (upstream
 * default): in unit-test mode `\\wsl.localhost` paths resolve through the ijent-backed EEL machine,
 * and the ijent plugin declares a `testServiceImplementation` (`TestIjentExecFileProvider`) that is
 * shipped in no IDE distribution, so the fixture fails with ClassNotFoundException (IJPL-178929 /
 * IJPL-222201). The `WSLDistribution` transport this test covers still exists in production and is
 * also exercised by `DaemonPathConversionTest`. Restore together with the TODO.md item once the
 * platform ships that class or removes `testServiceImplementation`.
 */
@TestApplication
@EnabledOnWsl
@Disabled("IJPL-178929: TestIjentExecFileProvider is absent from the IDE distribution")
internal class WslDcevmSupportTest {

    private val jdkHome by tempPathFixture(root = WslTestEnvironment.tmpRoot(), prefix = "ijpl-wsl-jdk-")

    @Test
    fun `jdk with enabled dcevm flag resolves auto`() {
        WslTestEnvironment.createFakeJdk(
            jdkHome,
            " bool AllowEnhancedClassRedefinition = true {product}",
        )
        val support = runBlocking { detectDcevmSupport(jdkHome) }
        Assertions.assertEquals(DCEVMSupport.Auto, support)
    }

    @Test
    fun `jdk with disabled dcevm flag requires args`() {
        WslTestEnvironment.createFakeJdk(
            jdkHome,
            " bool AllowEnhancedClassRedefinition = false {product}",
        )
        val support = runBlocking { detectDcevmSupport(jdkHome) }
        Assertions.assertEquals(DCEVMSupport.RequiresArg, support)
    }

    @Test
    fun `jdk without dcevm flag resolves none`() {
        WslTestEnvironment.createFakeJdk(
            jdkHome,
            " bool UseCompressedOops = true {product}",
        )
        val support = runBlocking { detectDcevmSupport(jdkHome) }
        Assertions.assertEquals(DCEVMSupport.None, support)
    }

    @Test
    fun `alt-jvm layout resolves altJvm without process execution`() {
        WslTestEnvironment.createAltJvmLayout(jdkHome)
        val support = runBlocking { detectDcevmSupport(jdkHome) }
        Assertions.assertEquals(DCEVMSupport.AltJvm, support)
    }
}
