/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.spotless.gradle

import com.intellij.openapi.components.service
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

@TestApplication
internal class SpotlessGradleSettingsTest {
    private val projectFixture = projectFixture(
        pathFixture = tempPathFixture(),
        openAfterCreation = true,
    )

    @Test
    fun `each Gradle sync advances provider state even when imported data is unchanged`() {
        val settings = projectFixture.get().service<SpotlessGradleSettings>()
        val initial = settings.providerState.value

        settings.updateFrom(emptyList())
        val firstSync = settings.providerState.value
        settings.updateFrom(emptyList())
        val secondSync = settings.providerState.value

        assertNotEquals(initial, firstSync)
        assertNotEquals(firstSync, secondSync)
    }

    @Test
    fun `daemon configuration changes publish distinct provider state`() {
        val settings = projectFixture.get().service<SpotlessGradleSettings>()
        val initial = settings.providerState.value

        settings.gradleDaemonVersion = "0.8.0"
        val versionChanged = settings.providerState.value
        settings.gradleDaemonVersion = "0.8.0"
        assertSame(versionChanged, settings.providerState.value)

        settings.gradleDaemonJar = "/tmp/spotless-daemon.jar"
        val jarChanged = settings.providerState.value

        assertNotEquals(initial, versionChanged)
        assertNotEquals(versionChanged, jarChanged)
    }
}
