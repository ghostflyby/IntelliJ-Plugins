/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mill

import com.intellij.testFramework.junit5.TestApplication
import dev.ghostflyby.mill.command.MillCommandLineUtil
import dev.ghostflyby.mill.settings.MillExecutableSource
import dev.ghostflyby.mill.settings.MillExecutionSettings
import dev.ghostflyby.mill.settings.MillExecutionTarget
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@TestApplication
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
            Files.writeString(root.resolve(MillConstants.WRAPPER_SCRIPT_NAME), "#!/bin/sh\n")

            val resolved = MillCommandLineUtil.resolveExecutable(
                projectRoot = root,
                executableSource = MillExecutableSource.MANUAL,
                configuredExecutablePath = MillConstants.DEFAULT_EXECUTABLE,
            )

            assertEquals(MillConstants.DEFAULT_EXECUTABLE, resolved)
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

    @Test
    fun `converts wsl unc paths to target paths`() {
        val target = MillExecutionTarget.Wsl("Ubuntu-24.04")

        assertEquals(
            "/home/user/proj",
            MillCommandLineUtil.toTargetPath(target, Path.of("""\\wsl.localhost\Ubuntu-24.04\home\user\proj""")),
        )
        assertEquals(
            "/home/user/proj",
            MillCommandLineUtil.toTargetPath(target, Path.of("""\\wsl$\ubuntu-24.04\home\user\proj""")),
        )
        assertEquals("/", MillCommandLineUtil.toTargetPath(target, Path.of("""\\wsl.localhost\Ubuntu-24.04""")))
        assertEquals("mill", MillCommandLineUtil.toTargetPath(target, Path.of("mill")))
    }

    @Test
    fun `converts absolute target paths to wsl unc host paths`() {
        val target = MillExecutionTarget.Wsl("Ubuntu-24.04")

        assertEquals(
            """\\wsl.localhost\Ubuntu-24.04\home\user\proj""",
            MillCommandLineUtil.toHostPath(target, "/home/user/proj"),
        )
        assertEquals("3.8.2", MillCommandLineUtil.toHostPath(target, "3.8.2"))
        assertEquals("/home/user/proj", MillCommandLineUtil.toHostPath(MillExecutionTarget.Local, "/home/user/proj"))
    }

    @Test
    fun `builds wsl exe command line for wsl targets`() {
        val target = MillExecutionTarget.Wsl("Ubuntu-24.04")
        val root = Path.of("""\\wsl.localhost\Ubuntu-24.04\home\user\proj""")

        val argv = MillCommandLineUtil.buildWslCommandLine(target, root, listOf("mill", "show", "version"))

        assertEquals(
            listOf("wsl.exe", "-d", "Ubuntu-24.04", "--cd", "/home/user/proj", "--exec", "mill", "show", "version"),
            argv,
        )
    }

    private fun deleteRecursively(root: Path) {
        Files.walk(root)
            .sorted(Comparator.reverseOrder())
            .forEach(Files::deleteIfExists)
    }
}
