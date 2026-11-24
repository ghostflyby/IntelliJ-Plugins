/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mill.settings

import java.io.Serializable

/**
 * Where a linked Mill project (and with it the Mill executable) lives. Carried through
 * [MillExecutionSettings] into the external-system remote process, so everything that consumes
 * it must stay free of IDE platform APIs — plain `wsl.exe` process calls and string-level path
 * conversion only.
 */
public sealed class MillExecutionTarget : Serializable {
    /** The IDE host itself. */
    public data object Local : MillExecutionTarget() {
        private fun readResolve(): Any = Local
    }

    /** A WSL distribution; [distribution] is the name `wsl.exe -d` accepts. */
    public data class Wsl(public val distribution: String) : MillExecutionTarget()

    /**
     * Any other EEL target (dev containers, remotes): detected so imports can fail with a clear
     * message; execution there is not wired up yet.
     */
    public data class Eel(public val description: String) : MillExecutionTarget()

    internal companion object {
        private const val serialVersionUID = 1L
    }
}
