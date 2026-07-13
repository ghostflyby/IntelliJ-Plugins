/*
 * Copyright (c) 2025-2026 ghostflyby
 * SPDX-FileCopyrightText: 2025-2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.spotless

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Path

/**
 * Public extension-point contract for resolving and starting a Spotless daemon.
 *
 * The daemon HTTP API is owned by SpotlessDaemon and is not part of this contract.
 */
public interface SpotlessDaemonProvider {

    /**
     * Check if Spotless is applicable to the given project, called before calling [startDaemon]
     * Note: return false if you are not sure yet, the external system sync may not have completed yet
     * @return `true` if Spotless can be used for the given project, `false` otherwise or the external system sync has not yet completed
     */
    public fun isApplicableTo(project: Project): Boolean

    /**
     * Find the external project and daemon-side file path for [virtualFile].
     */
    public fun findTarget(project: Project, virtualFile: VirtualFile): SpotlessDaemonTarget?

    /**
     * Start a Spotless daemon for [externalProject].
     *
     * Provider-specific process resources must be bound to [lifecycle]. The core registry owns
     * that lifecycle and closes it when the daemon is released.
     *
     * @param externalProject The external project path as returned by [findTarget]
     * see https://github.com/ghostflyby/SpotlessDaemon#http-api for the http service api details
     */
    public suspend fun startDaemon(
        project: Project,
        externalProject: Path,
        lifecycle: SpotlessDaemonLifecycle,
    ): SpotlessDaemonHost
}

public interface SpotlessDaemonLifecycle {
    /** Ask the core registry to detach this daemon after natural process termination. */
    public fun requestClose(reason: String)

    /**
     * Register synchronous provider-owned cleanup.
     *
     * Cleanups run in LIFO order exactly once after core state has been detached. They must
     * promptly cancel or destroy provider-owned resources and must not perform long-running work.
     */
    public fun onClose(cleanup: () -> Unit)
}

public data class SpotlessDaemonTarget(
    public val externalProject: Path,
    public val file: Path,
)
