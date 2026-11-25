/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

@file:Suppress("UnstableApiUsage")
// IDE-side target detection only: the EEL provider surface is Experimental and absent from the
// external-system remote process, so nothing under dev.ghostflyby.mill.command may reach this
// file. Track platform API changes here (see EnhancedHotSwapEnabler-EelSupport.md for the WSL
// gate caveat this detection mirrors).

package dev.ghostflyby.mill.settings

import com.intellij.execution.wsl.WslPath
import com.intellij.platform.eel.provider.LocalEelDescriptor
import com.intellij.platform.eel.provider.getEelDescriptor
import java.nio.file.Path

internal object MillExecutionTargets {
    fun detect(projectRoot: Path): MillExecutionTarget {
        // WSL detection must not rely on the EEL descriptor: WslEelProvider is gated by
        // WslIjentAvailabilityService.useIjentForWslNioFileSystem(), so WSL UNC paths resolve to
        // LocalEelDescriptor when the gate is off and the descriptor route misses them.
        val wslPath = runCatching { WslPath.parseWindowsUncPath(projectRoot.toString()) }.getOrNull()
        if (wslPath != null) {
            return MillExecutionTarget.Wsl(wslPath.distribution.id)
        }
        // The EEL provider may be unavailable or backed by a daemon that is down (observed in
        // headless test environments); probing must never break the flow — fall back to local.
        return runCatching {
            val descriptor = projectRoot.getEelDescriptor()
            if (descriptor == LocalEelDescriptor) {
                MillExecutionTarget.Local
            } else {
                MillExecutionTarget.Eel(descriptor.toString())
            }
        }.getOrDefault(MillExecutionTarget.Local)
    }
}
