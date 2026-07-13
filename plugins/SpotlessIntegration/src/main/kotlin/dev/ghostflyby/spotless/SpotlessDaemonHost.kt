/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.spotless

import java.nio.file.Path

/**
 * Public daemon address contract used by provider implementations and the core daemon controller.
 */
public sealed interface SpotlessDaemonHost {
    public data class Localhost(public val port: Int) : SpotlessDaemonHost

    public data class Unix(
        public val path: Path,
        public val workingDirectory: Path,
    ) : SpotlessDaemonHost

}
