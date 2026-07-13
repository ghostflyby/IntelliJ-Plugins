/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.spotless

/**
 * Public project service interface for provider-owned lifecycle callbacks.
 */
public interface SpotlessDaemonControl {
    public fun releaseDaemon(host: SpotlessDaemonHost)
}

