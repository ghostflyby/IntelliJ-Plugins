/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.spotless.gradle.frontend

import dev.ghostflyby.spotless.Bundle
import dev.ghostflyby.spotless.api.frontend.SpotlessDaemonProviderPresentation

internal class SpotlessGradlePresentation : SpotlessDaemonProviderPresentation {
    override val providerId: String = "dev.ghostflyby.spotless.gradle"

    override val presentableName: String
        get() = Bundle.message("spotless.provider.gradle.presentable.name")
}
