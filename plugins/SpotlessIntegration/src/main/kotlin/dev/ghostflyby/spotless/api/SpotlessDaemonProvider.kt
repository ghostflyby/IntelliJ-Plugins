/*
 * Copyright (c) 2025-2026 ghostflyby
 * SPDX-FileCopyrightText: 2025-2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.spotless.api

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.annotations.ApiStatus
import java.nio.file.Path

/**
 * Public extension-point contract for resolving and starting a Spotless daemon.
 *
 * The daemon HTTP API is owned by SpotlessDaemon and is not part of this contract.
 */
@ApiStatus.OverrideOnly
public interface SpotlessDaemonProvider {
    public companion object {
        @JvmField
        public val EP_NAME: ExtensionPointName<SpotlessDaemonProvider> =
            ExtensionPointName.create("dev.ghostflyby.spotless.spotlessDaemonProvider")
    }

    /** Stable provider identity shared by backend coordination and frontend presentation. */
    public val id: String

    /**
     * Return the current project-scoped provider state.
     *
     * The returned flow must be owned by a project-level lifecycle and remain stable across calls.
     * Each external project has an explicit generation. Providers must change that generation when
     * any input affecting [resolveTarget] or [startDaemon] changes for the project root.
     */
    public fun state(project: Project): StateFlow<State>

    /**
     * Resolve the daemon ownership and daemon-side path for [file].
     *
     * This method must be cheap and must return `null` when this provider cannot currently handle
     * the file. The returned external project must be one of
     * [State.projects]. The core tries later providers when a
     * provider returns `null` or an invalid target. Once a provider returns the first valid target,
     * it owns that request; startup or daemon-operation failures do not fall back to another
     * provider. Provider iteration follows IntelliJ extension-point ordering.
     */
    public fun resolveTarget(project: Project, file: VirtualFile): Target?

    /**
     * Start one daemon for [StartContext.externalProjectRoot].
     *
     * Resources remain provider-owned until this method returns a handle. If startup fails or is
     * cancelled before returning, the provider must clean them up directly. The returned lifetime
     * job must already be started, must not be a child of this invocation, and must not complete
     * until all provider-owned daemon resources have been released. The core owns HTTP readiness
     * and stop requests after the handle is returned.
     *
     * see https://github.com/ghostflyby/SpotlessDaemon#http-api for the http service api details
     */
    public suspend fun startDaemon(context: StartContext): Handle

    /** Immutable project-scoped state published by a daemon provider. */
    public data class State(
        /** External projects where the provider has positively detected Spotless. */
        public val projects: List<ExternalProject>,
    )

    /** Provider inputs for one external project. */
    public data class ExternalProject(
        public val root: Path,
        public val generation: Long,
    )

    public data class Target(
        public val externalProjectRoot: Path,
        public val file: Path,
    )

    /** Startup inputs supplied by the core when creating a provider daemon handle. */
    public interface StartContext {
        public val project: Project
        public val externalProjectRoot: Path
    }

    /**
     * Immutable daemon endpoint plus its provider-owned asynchronous lifetime.
     *
     * The core or provider may cancel [lifetime]. Joining it waits for the lifetime block, including its
     * `finally` cleanup, but does not propagate the provider failure that ended the backing task.
     */
    public class Handle(
        public val endpoint: Endpoint,
        public val lifetime: Job,
    )

    /** Public daemon address contract used by providers and the core daemon client. */
    public sealed interface Endpoint {
        public data class Localhost(public val port: UShort) : Endpoint

        public data class UnixSocket(public val path: Path) : Endpoint
    }
}
