/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mill

import com.intellij.execution.configurations.SimpleJavaParameters
import com.intellij.openapi.application.PathManager
import com.intellij.testFramework.junit5.TestApplication
import kotlinx.coroutines.Deferred
import kotlinx.serialization.StringFormat
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The external-system remote process gets its classpath from the manager and resolver classes
 * only (plus whatever [MillExternalSystemManager.enhanceRemoteProcessing] adds), so this pins
 * that the plugin's own content-module jars and the JSON/coroutines jars are all reachable
 * there — the resolver and the deserialized execution settings need them.
 */
@TestApplication
internal class MillRemoteProcessingClasspathTest {

    @Test
    fun `remote processing classpath exposes the shared content module and serialization jars`() {
        val parameters = SimpleJavaParameters()

        MillExternalSystemManager().enhanceRemoteProcessing(parameters)

        val classPath = parameters.classPath.pathList
        assertTrue(
            classPath.contains(PathManager.getJarPathForClass(MillConstants::class.java)),
            "the shared content module jar (settings, constants) must be on the remote classpath: $classPath",
        )
        assertTrue(classPath.contains(PathManager.getJarPathForClass(Json::class.java)), "kotlinx-serialization-json missing: $classPath")
        assertTrue(classPath.contains(PathManager.getJarPathForClass(StringFormat::class.java)), "kotlinx-serialization-core missing: $classPath")
        assertTrue(classPath.contains(PathManager.getJarPathForClass(Deferred::class.java)), "kotlinx-coroutines missing: $classPath")
        assertEquals(4, classPath.size, "unexpected extra remote classpath entries: $classPath")
    }
}
