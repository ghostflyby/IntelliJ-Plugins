/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mill

import com.intellij.openapi.externalSystem.model.Key
import com.intellij.openapi.externalSystem.model.project.AbstractExternalEntityData

internal class MillProjectJdkData(
    val jdkHomePath: String,
) : AbstractExternalEntityData(MillConstants.systemId) {
    companion object {
        @JvmField
        val key: Key<MillProjectJdkData> = Key.create(MillProjectJdkData::class.java, 226)
    }
}
