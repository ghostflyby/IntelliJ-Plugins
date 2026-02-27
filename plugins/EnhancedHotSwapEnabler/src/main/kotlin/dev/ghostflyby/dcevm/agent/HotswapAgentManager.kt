/*
 * Copyright (c) 2025-2026 ghostflyby
 * SPDX-FileCopyrightText: 2025-2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 *
 * This file is part of IntelliJ-Plugins by ghostflyby
 *
 * IntelliJ-Plugins by ghostflyby is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3.0 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, see
 * <https://www.gnu.org/licenses/>.
 */

package dev.ghostflyby.dcevm.agent

import com.intellij.openapi.application.PathManager
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

private val HOTSWAP_AGENT_MAIN_JAR_PATTERN =
    Regex("^hotswap-agent-[0-9][0-9A-Za-z._+-]*\\.jar$")

private object BundledHotSwapAgentJarAnchor

/**
 * Bundled HotswapAgent jar path resolved from plugin distribution.
 * The jar is copied by Gradle into plugin `lib/` during sandbox/package preparation.
 */
internal val BundledHotSwapAgentJarPath: Path by lazy(LazyThreadSafetyMode.PUBLICATION) {
    val classpathEntry = requireNotNull(PathManager.getJarPathForClass(BundledHotSwapAgentJarAnchor::class.java)) {
        "Unable to resolve plugin classpath entry for HotSwapAgent"
    }
    val libDir = requireNotNull(Path.of(classpathEntry).parent) {
        "Unable to resolve plugin lib directory for HotSwapAgent"
    }
    Files.list(libDir).use { files ->
        files
            .filter { Files.isRegularFile(it) }
            .filter { path -> HOTSWAP_AGENT_MAIN_JAR_PATTERN.matches(path.name) }
            .sorted(compareByDescending(Path::toString))
            .findFirst()
            .orElseThrow {
                IllegalStateException("Bundled HotSwapAgent jar is missing in plugin lib directory: $libDir")
            }
    }
}
