/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.dcevm.wsl

import com.intellij.execution.wsl.WslPath
import com.intellij.util.system.OS
import org.junit.jupiter.api.Assumptions
import java.nio.file.Files
import java.nio.file.Path

/**
 * Environment for WSL functional tests: the distribution name is forwarded from the CI WSL job
 * (or local WSL development) via `-PwslDistro=<distro>` as the `wsl.distro` system property by the
 * `repo.intellij-lib` convention plugin; tests skip themselves via [assumeAvailable] when unset.
 */
internal object WslTestEnvironment {

    val distro: String?
        get() = System.getProperty("wsl.distro")?.takeIf { it.isNotBlank() }

    fun requireDistro(): String = requireNotNull(distro) { "wsl.distro is not set" }

    fun assumeAvailable() {
        Assumptions.assumeTrue(OS.CURRENT == OS.Windows) { "WSL tests require a Windows host" }
        Assumptions.assumeTrue(!distro.isNullOrBlank()) { "wsl.distro is not set; WSL tests are skipped" }
    }

    /** `\\wsl.localhost\<distro>\tmp`; falls back to a placeholder name when distro is unset (tests assume-skip first) */
    fun tmpRoot(): Path = Path.of("\\\\wsl.localhost", distro ?: "unset", "tmp")

    fun newIsolatedDir(prefix: String): Path = Files.createTempDirectory(tmpRoot(), prefix)

    /** Generates a fake `bin/java` under [jdkHome] (a POSIX script echoing one PrintFlagsFinal-style line) and marks it executable */
    fun createFakeJdk(jdkHome: Path, printFlagsFinalLine: String) {
        val bin = jdkHome.resolve("bin")
        Files.createDirectories(bin)
        Files.writeString(bin.resolve("java"), "#!/bin/sh\necho '$printFlagsFinalLine'\n")
        runWsl("chmod", "+x", toLinuxPath(jdkHome))
    }

    /** Creates the `lib/dcevm/` directory layout (simulating a DCEVM alt-jvm installation) */
    fun createAltJvmLayout(jdkHome: Path) {
        Files.createDirectories(jdkHome.resolve("lib").resolve("dcevm"))
    }

    fun runWsl(vararg command: String) {
        val process = ProcessBuilder("wsl.exe", "-d", requireDistro(), *command)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        check(exitCode == 0) {
            "wsl command failed (exit=$exitCode): wsl -d $distro ${command.joinToString(" ")}\n$output"
        }
    }

    /** Converts a `\\wsl.localhost\<distro>\...` UNC path into its in-distribution Linux path */
    fun toLinuxPath(wslUncPath: Path): String {
        val wsl = WslPath.parseWindowsUncPath(wslUncPath.toString())
            ?: error("Not a WSL UNC path: $wslUncPath")
        return wsl.linuxPath
    }
}
