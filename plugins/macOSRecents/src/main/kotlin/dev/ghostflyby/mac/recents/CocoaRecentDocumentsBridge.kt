/*
 * Copyright (c) 2025-2026 ghostflyby
 * SPDX-FileCopyrightText: 2025-2026 ghostflyby
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

package dev.ghostflyby.mac.recents

import com.intellij.openapi.application.UI
import com.intellij.ui.mac.foundation.Foundation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI

internal interface CocoaRecentDocumentsBridge {
    suspend fun appendRecentDocuments(uris: List<URI>)
    suspend fun replaceRecentDocuments(uris: List<URI>)
}

internal class FoundationCocoaRecentDocumentsBridge : CocoaRecentDocumentsBridge {
    override suspend fun appendRecentDocuments(uris: List<URI>) {
        updateRecentDocuments(uris = uris, clearExisting = false)
    }

    override suspend fun replaceRecentDocuments(uris: List<URI>) {
        updateRecentDocuments(uris = uris, clearExisting = true)
    }

    private suspend fun updateRecentDocuments(uris: List<URI>, clearExisting: Boolean) {
        if (uris.isEmpty() && !clearExisting) {
            return
        }

        withContext(Dispatchers.UI) {
            val controller = Foundation.invoke(documentControllerClass, "sharedDocumentController")
            if (clearExisting) {
                Foundation.invoke(controller, "clearRecentDocuments:", null)
            }
            uris.forEach { uri ->
                val nsUrl = Foundation.invoke(nsUrlClass, "URLWithString:", Foundation.nsString(uri.toString()))
                Foundation.invoke(controller, "noteNewRecentDocumentURL:", nsUrl)
            }
        }
    }

    private companion object {
        private val documentControllerClass = Foundation.getObjcClass("NSDocumentController")
        private val nsUrlClass = Foundation.getObjcClass("NSURL")
    }
}
