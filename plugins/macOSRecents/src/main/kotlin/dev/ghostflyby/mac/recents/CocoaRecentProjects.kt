/*
 * Copyright (c) 2025 ghostflyby <ghostflyby+intellij@outlook.com>
 *
 * This program is free software; you can redistribute it and/or
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

import com.intellij.ide.RecentProjectsManager
import com.intellij.ide.RecentProjectsManagerBase
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.ui.mac.foundation.Foundation
import java.net.URI
import kotlin.io.path.Path

internal class CocoaRecentProjectsListener : RecentProjectsManager.RecentProjectsChange {
    override fun change() {

        clearDocuments()

        val recentsManager = RecentProjectsManagerBase.getInstanceEx()

        recentsManager.getRecentPaths()
            .map { Path(it).toUri() }
            .forEach {
                addDocuments(it)
            }

    }
}

internal class StartUp : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.projectFilePath?.let {
            addDocuments(Path(it).toUri())
        }
    }

}

private fun addDocuments(url: URI) {
    val pool = Foundation.NSAutoreleasePool()
    try {
        val controllerClass = Foundation.getObjcClass("NSDocumentController")
        val controller = Foundation.invoke(controllerClass, "sharedDocumentController")
        val nsUrlClass = Foundation.getObjcClass("NSURL")
        val url = Foundation.invoke(nsUrlClass, "URLWithString:", Foundation.nsString(url.toString()))
        runInEdt {
            Foundation.invoke(controller, "noteNewRecentDocumentURL:", url)
        }


    } finally {
        pool.drain()
    }
}

private fun clearDocuments() {
    val pool = Foundation.NSAutoreleasePool()
    try {
        val controllerClass = Foundation.getObjcClass("NSDocumentController")
        val controller = Foundation.invoke(controllerClass, "sharedDocumentController")
        runInEdt {
            Foundation.invoke(controller, "clearRecentDocuments:", null)
        }
    } finally {
        pool.drain()
    }
}
