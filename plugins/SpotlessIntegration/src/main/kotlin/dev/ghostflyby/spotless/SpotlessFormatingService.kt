/*
 * Copyright (c) 2025 ghostflyby
 * SPDX-FileCopyrightText: 2025 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 *
 * This file is part of IntelliJ-Plugins by ghostflyby
 *
 * IntelliJ-Plugins by ghostflyby is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3.0 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, see
 * <https://www.gnu.org/licenses/>.
 */

package dev.ghostflyby.spotless

import com.intellij.formatting.service.AsyncDocumentFormattingService
import com.intellij.formatting.service.AsyncFormattingRequest
import com.intellij.formatting.service.FormattingService
import com.intellij.openapi.util.NlsSafe
import com.intellij.psi.PsiFile

public class SpotlessFormatingService : AsyncDocumentFormattingService() {
    override fun createFormattingTask(formattingRequest: AsyncFormattingRequest): FormattingTask {
        formattingRequest.context
        TODO("Not yet implemented")
    }

    override fun getNotificationGroupId(): String {
        return spotlessNotificationGroupId
    }

    override fun getName(): @NlsSafe String {
        return "Spotless"
    }

    override fun getFeatures(): Set<FormattingService.Feature?> = emptySet()

    override fun canFormat(file: PsiFile): Boolean {
        file.virtualFile.toNioPath()
        TODO("Not yet implemented")
    }
}