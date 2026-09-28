/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mill

import com.intellij.openapi.externalSystem.service.execution.AbstractExternalSystemTaskConfigurationType

internal class MillExternalTaskConfigurationType : AbstractExternalSystemTaskConfigurationType(MillConstants.systemId) {
    override fun getConfigurationFactoryId(): String = "Mill"

    override fun isDumbAware(): Boolean = true

    override fun isEditableInDumbMode(): Boolean = true
}
