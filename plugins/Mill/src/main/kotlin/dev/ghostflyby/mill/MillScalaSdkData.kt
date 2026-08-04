/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mill

import com.intellij.openapi.externalSystem.model.Key
import com.intellij.openapi.externalSystem.model.project.AbstractExternalEntityData

internal class MillScalaSdkData(
    val scalaVersion: String,
    val scalacClasspath: List<String>,
    val scaladocClasspath: List<String>,
    val replClasspath: List<String>,
) : AbstractExternalEntityData(MillConstants.systemId) {
    companion object {
        @JvmField
        val key: Key<MillScalaSdkData> = Key.create(MillScalaSdkData::class.java, 225)
    }
}
