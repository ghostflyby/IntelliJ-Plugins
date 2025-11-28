/*
 * Copyright (c) 2025 ghostflyby
 * SPDX-FileCopyrightText: 2025 ghostflyby
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

package dev.ghostflyby.spotless

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Path

public interface SpotlessDaemonProvider : Disposable {

    /**
     * Check if Spotless is applicable to the given project, called before calling [startDaemon]
     * Note: return false if you are not sure yet, the external system sync may not have completed yet
     * @return `true` if Spotless can be used for the given project, `false` otherwise or the external system sync has not yet completed
     */
    public fun isApplicableTo(project: Project): Boolean

    /**
     * Start a Spotless Daemon for the given project, the caller is responsible for
     * @return a newly started [SpotlessDaemonHost]
     * @param externalProject The external project path as returned by [findExternalProjectPath]
     * see https://github.com/ghostflyby/SpotlessDaemon#http-api for the http service api details
     */
    public suspend fun startDaemon(
        project: Project,
        externalProject: Path,
    ): SpotlessDaemonHost

    /**
     * Find the external project path for the given virtual file
     * @return Path to the external project or `null` if not found
     */
    public fun findExternalProjectPath(project: Project, virtualFile: VirtualFile): Path?

    /**
     * [Spotless] is automatically registered as the parent disposable of all [SpotlessDaemonProvider]s.
     *
     * You DON'T need to stop the daemons here, just clean up other resources if needed.
     *
     */
    override fun dispose() {

    }

}


public sealed interface SpotlessDaemonHost {
    public data class Localhost(val port: Int) : SpotlessDaemonHost
    public data class Unix(val path: Path) : SpotlessDaemonHost
}