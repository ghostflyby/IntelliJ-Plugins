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

package dev.ghostflyby.vitepress.preview

import com.intellij.ide.browsers.OpenInBrowserRequest
import com.intellij.ide.browsers.WebBrowserUrlProvider
import com.intellij.openapi.components.service
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.toNioPathOrNull
import com.intellij.util.Url
import com.intellij.util.Urls
import dev.ghostflyby.vitepress.VitePressFiletype
import dev.ghostflyby.vitepress.isVitePressRoot
import java.nio.file.Path

internal class VitePressUrlProvider : WebBrowserUrlProvider() {
    override fun canHandleElement(request: OpenInBrowserRequest): Boolean {
        val project = request.project
        val file = request.virtualFile ?: return false
        if (!file.isVitePressFileType()) return false
        val root = findNearestVitePressRoot(file) ?: return false
        val rootPath = root.toNioPathOrNull() ?: return false
        return project.service<VitePressPreviewUrlStore>().get(rootPath) != null
    }

    override fun getUrl(request: OpenInBrowserRequest, file: VirtualFile): Url? {
        val project = request.project
        if (!file.isVitePressFileType()) return null
        val root = findNearestVitePressRoot(file) ?: return null
        val rootPath = root.toNioPathOrNull() ?: return null
        val targetPath = file.toNioPathOrNull() ?: return null
        val baseUrl = project.service<VitePressPreviewUrlStore>().get(rootPath) ?: return null
        val pagePath = buildPreviewRelativePath(rootPath, targetPath)
        val resolved = runCatching {
            baseUrl.resolve(pagePath).toASCIIString()
        }.getOrNull() ?: return null
        return Urls.newFromEncoded(resolved)
    }
}

private fun VirtualFile.isVitePressFileType(): Boolean {
    return FileTypeManager.getInstance().isFileOfType(this, VitePressFiletype)
}

private fun buildPreviewRelativePath(rootPath: Path, targetPath: Path): String {
    val relativePath = rootPath.relativize(targetPath)
    val normalized = relativePath.normalize().toString()
    if (!normalized.endsWith(MARKDOWN_SUFFIX, ignoreCase = true)) return normalized
    val withoutExtension = normalized.substring(0, normalized.length - MARKDOWN_SUFFIX.length)
    if (withoutExtension == INDEX_PAGE_NAME) return ""
    if (withoutExtension.endsWith("/$INDEX_PAGE_NAME")) return withoutExtension.removeSuffix(INDEX_PAGE_NAME)
    return withoutExtension
}

private fun findNearestVitePressRoot(file: VirtualFile): VirtualFile? {
    var current: VirtualFile? = if (file.isDirectory) file else file.parent
    while (current != null) {
        if (current.isVitePressRoot()) return current
        current = current.parent
    }
    return null
}

private const val MARKDOWN_SUFFIX: String = ".md"
private const val INDEX_PAGE_NAME: String = "index"
