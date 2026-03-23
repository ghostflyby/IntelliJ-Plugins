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

import com.intellij.ide.RecentProjectsManager
import com.intellij.ide.RecentProjectsManagerBase
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

internal class CocoaRecentProjectsListener : RecentProjectsManager.RecentProjectsChange {
    override fun change() {
        val recentPaths = RecentProjectsManagerBase.getInstanceEx().getRecentPaths()
        service<CocoaRecentProjectsSyncService>().scheduleSync(recentPaths = recentPaths)
    }
}

internal class StartUp : ProjectActivity {
    override suspend fun execute(project: Project) {
        val recentPaths = RecentProjectsManagerBase.getInstanceEx().getRecentPaths()
        service<CocoaRecentProjectsSyncService>().scheduleSync(
            recentPaths = recentPaths,
            startupProjectPath = project.projectFilePath,
        )
    }
}
