/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mill.script

import dev.ghostflyby.mill.Bundle
import org.jetbrains.plugins.scala.LanguageFileTypeBase
import org.jetbrains.plugins.scala.ScalaLanguage
import org.jetbrains.plugins.scala.finder.FileTypeWithIsolatedDeclarations
import org.jetbrains.plugins.scala.finder.ScalaLanguageDerivative
import org.jetbrains.plugins.scala.icons.Icons
import javax.swing.Icon

internal object MillBuildScriptFileType : LanguageFileTypeBase(ScalaLanguage.INSTANCE), FileTypeWithIsolatedDeclarations {
    override fun getName(): String = Bundle.message("filetype.mill.name")

    override fun getDescription(): String = Bundle.message("filetype.mill.description")

    override fun getDefaultExtension(): String = "mill"

    override fun getIcon(): Icon = Icons.MILL_FILE
}

internal class MillBuildScriptScalaLanguageDerivative : ScalaLanguageDerivative() {
    override fun getFileType() = MillBuildScriptFileType
}
