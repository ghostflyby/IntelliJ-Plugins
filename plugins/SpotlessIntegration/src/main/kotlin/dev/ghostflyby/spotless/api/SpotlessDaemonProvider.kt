/*
 * Copyright (c) 2025-2026 ghostflyby
 * SPDX-FileCopyrightText: 2025-2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.spotless.api

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.flow.StateFlow
import java.nio.file.Path

/**
 * Public extension-point contract for resolving and starting a Spotless daemon.
 *
 * The daemon HTTP API is owned by SpotlessDaemon and is not part of this contract.
 */
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
     * any input affecting [resolveTarget] or [runDaemon] changes for the project root.
     */
    public fun state(project: Project): StateFlow<SpotlessDaemonProviderState>

    /**
     * Resolve the daemon ownership and daemon-side path for [file].
     *
     * This method must be cheap and must return `null` when this provider cannot currently handle
     * the file. The returned external project must be one of
     * [SpotlessDaemonProviderState.projects]. The core tries later providers when a
     * provider returns `null` or an invalid target. Once a provider returns the first valid target,
     * it owns that request; startup or daemon-operation failures do not fall back to another
     * provider. Provider iteration follows IntelliJ extension-point ordering.
     */
    public fun resolveTarget(project: Project, file: VirtualFile): SpotlessDaemonTarget?

    /**
     * Run one daemon for [SpotlessDaemonRunContext.externalProjectRoot].
     *
     * This function suspends for the entire daemon lifetime. Returning or throwing initiates
     * provider-side termination. Coroutine cancellation initiates core-side termination.
     * Provider-owned resources must be released from `finally`; the core owns HTTP readiness and
     * stop requests.
     *
     * see https://github.com/ghostflyby/SpotlessDaemon#http-api for the http service api details
     */
    public suspend fun runDaemon(context: SpotlessDaemonRunContext)
}

/** Immutable project-scoped state published by a daemon provider. */
public data class SpotlessDaemonProviderState(
    /** External projects where the provider has positively detected Spotless. */
    public val projects: List<ExternalProject>,
)

/** Provider inputs for one external project. */
public data class ExternalProject(
    public val root: Path,
    public val generation: Long,
)

/** Lifetime-scoped context supplied by the core when running a provider daemon. */
public interface SpotlessDaemonRunContext {
    public val project: Project
    public val externalProjectRoot: Path

    /**
     * Publish the daemon connection address exactly once.
     *
     * The core performs HTTP readiness checks after publication. Repeated publication, publication
     * after the daemon has been detached, or returning without publication fails startup.
     */
    public suspend fun publishEndpoint(endpoint: SpotlessDaemonEndpoint)
}

/** Public daemon address contract used by providers and the core daemon client. */
public sealed interface SpotlessDaemonEndpoint {
    public data class Localhost(public val port: UShort) : SpotlessDaemonEndpoint

    public data class UnixSocket(public val path: Path) : SpotlessDaemonEndpoint
}

public data class SpotlessDaemonTarget(
    public val externalProjectRoot: Path,
    public val file: Path,
)
