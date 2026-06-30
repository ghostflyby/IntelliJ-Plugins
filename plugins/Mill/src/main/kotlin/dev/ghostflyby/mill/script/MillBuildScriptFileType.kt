/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mill.script

import org.jetbrains.plugins.scala.LanguageFileTypeBase
import org.jetbrains.plugins.scala.ScalaLanguage
import org.jetbrains.plugins.scala.finder.FileTypeWithIsolatedDeclarations
import org.jetbrains.plugins.scala.finder.ScalaLanguageDerivative
import org.jetbrains.plugins.scala.icons.Icons
import javax.swing.Icon

internal object MillBuildScriptFileType : LanguageFileTypeBase(ScalaLanguage.INSTANCE), FileTypeWithIsolatedDeclarations {
    override fun getName(): String = "Mill Build Script"

    override fun getDescription(): String = "Mill build script"

    override fun getDefaultExtension(): String = "mill"

    override fun getIcon(): Icon = Icons.MILL_FILE
}

internal class MillBuildScriptScalaLanguageDerivative : ScalaLanguageDerivative() {
    override fun getFileType() = MillBuildScriptFileType
}
