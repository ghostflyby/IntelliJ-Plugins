/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mill

import com.intellij.openapi.externalSystem.model.Key
import java.io.Serial
import java.io.Serializable

internal object MillTasksData : Serializable {
    val key: Key<MillTasksData> = Key.create(MillTasksData::class.java, 227)

    @Serial
    private const val serialVersionUID: Long = 1L

    @Serial
    private fun readResolve(): Any = MillTasksData
}
