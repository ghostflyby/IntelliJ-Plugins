/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mill.java

import com.intellij.openapi.externalSystem.model.Key
import com.intellij.openapi.externalSystem.model.project.AbstractExternalEntityData
import dev.ghostflyby.mill.MillConstants

internal class MillProjectJdkData(
    val jdkHomePath: String,
) : AbstractExternalEntityData(MillConstants.SYSTEM_ID) {
    companion object {
        @JvmField
        val KEY: Key<MillProjectJdkData> = Key.create(MillProjectJdkData::class.java, 226)
    }
}
