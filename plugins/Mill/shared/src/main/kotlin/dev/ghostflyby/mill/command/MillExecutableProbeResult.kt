/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mill.command

import java.nio.file.Path

/**
 * Pure data shared by the backend (discovery/probing implementation) and the frontend
 * (settings view model): no process handling lives in this file.
 */
public data class MillExecutableDiscovery(
    public val projectWrapper: Path?,
    public val pathExecutables: List<Path>,
)

/** Pure data describing an executable probe result; see [MillExecutableDiscovery]. */
public data class MillExecutableProbeResult(
    public val resolvedExecutable: String,
    public val isValid: Boolean,
    public val version: String?,
    public val errorDetails: String?,
)
