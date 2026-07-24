/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.spotless.api.frontend

import com.intellij.openapi.extensions.ExtensionPointName
import org.jetbrains.annotations.Nls

/** Frontend presentation for a backend Spotless daemon provider. */
public interface SpotlessDaemonProviderPresentation {
    public companion object {
        @JvmField
        public val EP_NAME: ExtensionPointName<SpotlessDaemonProviderPresentation> =
            ExtensionPointName.create("dev.ghostflyby.spotless.spotlessDaemonProviderPresentation")
    }

    /** Stable [dev.ghostflyby.spotless.api.SpotlessDaemonProvider.id] represented by this UI contribution. */
    public val providerId: String

    /** Human-readable provider source shown in project-level Spotless UI. */
    @get:Nls
    public val presentableName: String
}
