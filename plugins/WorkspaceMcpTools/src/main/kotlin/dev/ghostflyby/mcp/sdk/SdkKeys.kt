/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.sdk

import io.ktor.util.*

internal object SdkKeys {
    val ProjectProvider = AttributeKey<WorkspaceProjectProvider>("projectProvider")
    val InstanceKey = AttributeKey<String>("instanceKey")
}
