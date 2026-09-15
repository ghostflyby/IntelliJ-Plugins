/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mill

import dev.ghostflyby.mill.command.MillCommandLineUtil
import dev.ghostflyby.mill.settings.MillExecutableSource
import dev.ghostflyby.mill.settings.MillExecutionSettings
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal class MillCommandLineUtilTest {
    @Test
    fun `accepts minimum supported mill version`() {
        assertNull(MillCommandLineUtil.validateSupportedMillVersion("1.1.5"))
        assertNull(MillCommandLineUtil.validateSupportedMillVersion("1.1.6"))
    }

    @Test
    fun `accepts mill versions with semver build metadata`() {
        assertNull(MillCommandLineUtil.validateSupportedMillVersion("1.1.5+12"))
    }

    @Test
    fun `rejects mill versions older than minimum supported version`() {
        val validationMessage = MillCommandLineUtil.validateSupportedMillVersion("1.1.4")

        assertNotNull(validationMessage)
    }

    @Test
    fun `rejects mill versions with semver prerelease lower than minimum`() {
        val validationMessage = MillCommandLineUtil.validateSupportedMillVersion("1.1.5-RC1")

        assertNotNull(validationMessage)
    }

    @Test
    fun `resolves bare manual mill command via path even when project wrapper exists`() {
        val root = Files.createTempDirectory("mill-project")
        try {
            Files.writeString(root.resolve(MillConstants.wrapperScriptName), "#!/bin/sh\n")

            val resolved = MillCommandLineUtil.resolveExecutable(
                projectRoot = root,
                executableSource = MillExecutableSource.MANUAL,
                configuredExecutablePath = MillConstants.defaultExecutable,
            )

            assertEquals(MillConstants.defaultExecutable, resolved)
        } finally {
            deleteRecursively(root)
        }
    }

    @Test
    fun `interrupting runCommand destroys running process`() {
        val root = Files.createTempDirectory("mill-project")
        try {
            val script = root.resolve("slow-mill.sh")
            Files.writeString(
                script,
                """
                #!/bin/sh
                trap 'exit 143' TERM
                sleep 30
                """.trimIndent(),
            )
            script.toFile().setExecutable(true)
            val started = CountDownLatch(1)
            val stopped = CountDownLatch(1)
            val worker = Thread {
                try {
                    started.countDown()
                    MillCommandLineUtil.runCommand(
                        projectRoot = root,
                        settings = MillExecutionSettings().also {
                            it.millExecutableSource = MillExecutableSource.MANUAL
                            it.millExecutablePath = script.toString()
                        },
                        arguments = listOf("--version"),
                    )
                } catch (_: InterruptedException) {
                    // Expected: cancellation should interrupt runCommand and kill the process.
                } finally {
                    stopped.countDown()
                }
            }

            worker.start()
            assertTrue(started.await(1, TimeUnit.SECONDS))
            Thread.sleep(200)
            worker.interrupt()

            assertTrue(stopped.await(3, TimeUnit.SECONDS))
        } finally {
            deleteRecursively(root)
        }
    }

    private fun deleteRecursively(root: Path) {
        Files.walk(root)
            .sorted(Comparator.reverseOrder())
            .forEach(Files::deleteIfExists)
    }
}
