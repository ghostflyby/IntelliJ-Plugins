/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
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

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

public interface Spotless {
    /**
     * Public cleanup entry for provider implementations to release a started daemon explicitly.
     */
    public fun releaseDaemon(host: SpotlessDaemonHost)

    /**
     * Formats [content] using the applicable Spotless daemon for [virtualFile].
     *
     * The original file on disk is not modified. Consumers must inspect [SpotlessFormatResult]
     * to distinguish between successful formatting, unsupported files, and daemon failures.
     */
    public suspend fun format(
        project: Project,
        virtualFile: VirtualFile,
        content: CharSequence,
    ): SpotlessFormatResult

    /**
     * Strict capability check backed by Spotless daemon dry-run semantics.
     *
     * This returns `true` only when the daemon is reachable and reports the file as covered
     * without requiring content changes. It is intentionally stricter than a simple
     * provider/external-project lookup so formatter selection does not steal files from
     * other formatting services.
     */
    public suspend fun canFormat(project: Project, virtualFile: VirtualFile): Boolean

    /**
     * Synchronous bridge for formatter-selection code paths that cannot suspend.
     *
     * This preserves the strict [canFormat] semantics and may block for up to [timeout].
     * Prefer the suspending API for non-selection callers.
     */
    public fun canFormatSync(
        project: Project,
        virtualFile: VirtualFile,
        timeout: Duration = 500.milliseconds,
    ): Boolean
}
