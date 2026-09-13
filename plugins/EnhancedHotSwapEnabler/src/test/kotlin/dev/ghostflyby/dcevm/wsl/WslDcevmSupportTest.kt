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
import org.junit.jupiter.api.Test

/**
 * End-to-end WSL transport variant of `DcevmSupportDetectionTest`: runs the production
 * `detectDcevmSupport` routing (EEL exec for non-local descriptors) against a JDK living inside
 * the WSL distribution (`\\wsl.localhost\...` UNC home). The WSL branch must execute the ELF
 * inside the distribution instead of hitting Windows CreateProcess error=193.
 */
@TestApplication
@EnabledOnWsl
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
