/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.skills

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDirectory
import com.intellij.psi.SmartPointerManager
import com.intellij.refactoring.rename.RenameProcessor

internal fun renameSkillDirectory(
    project: Project,
    directory: PsiDirectory,
    newName: String,
) {
    if (!newName.isValidSkillName()) return
    if (directory.name == newName) return
    if (ApplicationManager.getApplication().isWriteAccessAllowed) {
        val directoryPtr = SmartPointerManager.createPointer(directory)
        ApplicationManager.getApplication().invokeLater {
            val currentDirectory = directoryPtr.element ?: return@invokeLater
            renameSkillDirectory(project, currentDirectory, newName)
        }
        return
    }
    RenameProcessor(project, directory, newName, false, false).run()
}
