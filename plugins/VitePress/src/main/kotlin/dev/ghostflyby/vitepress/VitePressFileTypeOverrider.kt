/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
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

package dev.ghostflyby.vitepress

import com.intellij.openapi.components.service
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.impl.FileTypeOverrider
import com.intellij.openapi.vfs.VirtualFile
import org.intellij.plugins.markdown.lang.MarkdownFileType

internal class VitePressFileTypeOverrider : FileTypeOverrider {
    override fun getOverriddenFileType(file: VirtualFile): FileType? {
        if (!file.extension.equals("md", ignoreCase = true)) {
            return null
        }
        val workaroundEnabled = service<VitePressMdFileTypeWorkaroundSettings>().isVueLanguageServiceWorkaroundEnabled
        return overriddenMarkdownFileType(
            isUnderVitePressRoot = file.isUnderVitePressRoot.value,
            isVueLanguageServiceWorkaroundEnabled = workaroundEnabled,
        )
    }
}

internal fun overriddenMarkdownFileType(
    isUnderVitePressRoot: Boolean,
    isVueLanguageServiceWorkaroundEnabled: Boolean,
): FileType? {
    return when {
        isUnderVitePressRoot -> VitePressFiletype
        isVueLanguageServiceWorkaroundEnabled -> MarkdownFileType.INSTANCE
        else -> null
    }
}
