/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp

import dev.ghostflyby.intellij.pluginVersion

internal val pluginVersion: String
    get() = WorkspaceMcpStartupActivity::class.java.pluginVersion
