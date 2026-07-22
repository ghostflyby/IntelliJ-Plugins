/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.spotless.gradle

import com.intellij.openapi.components.service
import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import org.jetbrains.plugins.gradle.settings.GradleProjectSettings
import org.jetbrains.plugins.gradle.settings.GradleSettings
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Path

@TestApplication
internal class SpotlessGradleSettingsTest {
    private val projectPathFixture = tempPathFixture()
    private val projectFixture = projectFixture(
        pathFixture = projectPathFixture,
        openAfterCreation = true,
    )

    @Test
    fun `each Gradle sync advances provider state even when imported data is unchanged`() {
        val (settings, root) = settingsWithDetectedRoot()
        val initial = settings.providerState.value

        settings.updateFrom(listOf(spotlessNode(root)))
        val firstSync = settings.providerState.value
        settings.updateFrom(listOf(spotlessNode(root)))
        val secondSync = settings.providerState.value

        assertNotEquals(initial, firstSync)
        assertNotEquals(firstSync, secondSync)
        assertEquals(
            listOf(1L, 2L, 3L),
            listOf(initial, firstSync, secondSync).map { state -> state.projects.single().generation },
        )
    }

    @Test
    fun `daemon configuration changes publish distinct provider state`() {
        val (settings, _) = settingsWithDetectedRoot()
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

    @Test
    fun `provider state stays equal while no external project is detected`() {
        val settings = projectFixture.get().service<SpotlessGradleSettings>()
        val initial = settings.providerState.value

        settings.updateFrom(emptyList())
        settings.gradleDaemonVersion = "0.8.0"

        assertSame(initial, settings.providerState.value)
    }

    private fun settingsWithDetectedRoot(): Pair<SpotlessGradleSettings, Path> {
        val project = projectFixture.get()
        val root = projectPathFixture.get().toAbsolutePath().normalize()
        GradleSettings.getInstance(project).linkProject(GradleProjectSettings(root.toString()))
        val settings = project.service<SpotlessGradleSettings>()
        settings.updateFrom(listOf(spotlessNode(root)))
        return settings to root
    }

    private fun spotlessNode(root: Path): DataNode<SpotlessGradleStateData> = DataNode(
        SpotlessGradleStateData.KEY,
        SpotlessGradleStateData(root, spotless = true),
        null,
    )
}
