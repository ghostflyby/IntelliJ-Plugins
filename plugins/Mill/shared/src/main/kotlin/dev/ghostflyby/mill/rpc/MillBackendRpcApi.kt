/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mill.rpc

import dev.ghostflyby.mill.command.MillExecutableDiscovery
import dev.ghostflyby.mill.command.MillExecutableProbeResult
import com.intellij.platform.rpc.RemoteApiProviderService
import fleet.rpc.RemoteApi
import fleet.rpc.Rpc
import fleet.rpc.remoteApiDescriptor
import kotlinx.serialization.Serializable
import java.nio.file.Path

/**
 * Host-side executable work exposed to the frontend. Discovery and probing spawn processes and
 * read host paths, so they must run in the IDE backend (or the monolith IDE); the frontend calls
 * them through the platform RPC channel. All parameters and DTOs are serializable; paths cross
 * the boundary as strings.
 */
@Rpc
public interface MillBackendRpcApi : RemoteApi<Unit> {
    public suspend fun discoverExecutables(projectPath: String): MillExecutableDiscoveryDto

    public suspend fun probeExecutable(
        projectPath: String,
        executableSource: String,
        executablePath: String,
    ): MillExecutableProbeDto

    public companion object {
        public suspend fun getInstance(): MillBackendRpcApi {
            return RemoteApiProviderService.resolve(remoteApiDescriptor<MillBackendRpcApi>())
        }
    }
}

@Serializable
public data class MillExecutableDiscoveryDto(
    public val projectWrapper: String? = null,
    public val pathExecutables: List<String> = emptyList(),
)

@Serializable
public data class MillExecutableProbeDto(
    public val resolvedExecutable: String,
    public val isValid: Boolean,
    public val version: String? = null,
    public val errorDetails: String? = null,
)

public fun MillExecutableDiscovery.toDto(): MillExecutableDiscoveryDto = MillExecutableDiscoveryDto(
    projectWrapper = projectWrapper?.toString(),
    pathExecutables = pathExecutables.map(Path::toString),
)

public fun MillExecutableDiscoveryDto.toDiscovery(): MillExecutableDiscovery = MillExecutableDiscovery(
    projectWrapper = projectWrapper?.let { runCatching { Path.of(it) }.getOrNull() },
    pathExecutables = pathExecutables.mapNotNull { runCatching { Path.of(it) }.getOrNull() },
)

public fun MillExecutableProbeResult.toDto(): MillExecutableProbeDto = MillExecutableProbeDto(
    resolvedExecutable = resolvedExecutable,
    isValid = isValid,
    version = version,
    errorDetails = errorDetails,
)

public fun MillExecutableProbeDto.toProbeResult(): MillExecutableProbeResult = MillExecutableProbeResult(
    resolvedExecutable = resolvedExecutable,
    isValid = isValid,
    version = version,
    errorDetails = errorDetails,
)
