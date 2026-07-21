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

    /** Human-readable provider source shown in project-level Spotless UI. */
    public val presentableName: String

    /**
     * Return the current project-scoped provider state.
     *
     * The returned flow must be owned by a project-level lifecycle and remain stable across calls.
     * Each emitted value must be immutable and implement structural equality over every input that
     * can affect [resolveTarget] or [startDaemon]. The core normalizes external-project paths and
     * restarts currently active provider daemons after every subsequent distinct state emission.
     * Providers that need event-based invalidation even when derived configuration is unchanged
     * should include a provider-private revision in their state implementation's equality.
     */
    public fun state(project: Project): StateFlow<State>

    /** Immutable, structurally comparable project-scoped state published by a provider. */
    public interface State {
        /** External projects where the provider has positively detected Spotless. */
        public val externalProjects: List<Path>
    }



    /**
     * Resolve the daemon ownership and daemon-side path for [file].
     *
     * This method must be cheap and must return `null` when this provider cannot currently handle
     * the file. The returned external project must be one of
     * [State.externalProjects]. The core tries later providers when a
     * provider returns `null`.
     */
    public fun resolveTarget(project: Project, file: VirtualFile): SpotlessDaemonTarget?

    /**
     * Start a Spotless daemon for [SpotlessDaemonStartContext.externalProjectRoot].
     *
     * Provider-specific process resources must be bound to
     * [SpotlessDaemonStartContext.lifecycle]. The core registry owns that lifecycle and closes it
     * when the daemon is released. HTTP readiness checks remain the core's responsibility.
     * This function must cooperate with coroutine cancellation: provider removal, replacement, or
     * project disposal can cancel an in-progress start. Register each resource as soon as it is
     * created so partial startup can be cleaned safely.
     *
     * see https://github.com/ghostflyby/SpotlessDaemon#http-api for the http service api details
     */
    public suspend fun startDaemon(context: SpotlessDaemonStartContext): Endpoint

    /**
     * Public daemon address contract used by provider implementations and the core daemon client.
     */
    public sealed interface Endpoint {
        public data class Localhost(public val port: UShort) : Endpoint

        public data class UnixSocket(public val path: Path) : Endpoint
    }
}

/** Invocation-scoped context supplied by the core when starting a provider daemon. */
public interface SpotlessDaemonStartContext {
    public val project: Project
    public val externalProjectRoot: Path
    public val lifecycle: SpotlessDaemonLifecycle
}

public interface SpotlessDaemonLifecycle {
    /**
     * Ask the core registry to detach this daemon after natural process termination.
     *
     * This method is non-blocking. Provider cleanup runs asynchronously after the daemon has been
     * removed from core state.
     */
    public fun requestClose(reason: String)

    /**
     * Register synchronous provider-owned cleanup.
     *
     * Cleanups run in LIFO order exactly once after core state has been detached. They must
     * promptly cancel or destroy provider-owned resources and must not perform long-running work.
     * If the lifecycle is already closed, [cleanup] runs synchronously before this method returns.
     */
    public fun registerCleanup(cleanup: () -> Unit)
}

public data class SpotlessDaemonTarget(
    public val externalProject: Path,
    public val file: Path,
)
