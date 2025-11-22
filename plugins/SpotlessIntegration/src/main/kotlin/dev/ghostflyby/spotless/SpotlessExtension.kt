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

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import java.nio.file.Path

public interface SpotlessExtension {
    public companion object {
        @JvmStatic
        public val EP_NAME: ExtensionPointName<SpotlessExtension> =
            ExtensionPointName.create<SpotlessExtension>("dev.ghostflyby.spotless.extension")
    }

    public fun isApplicableTo(project: Project, externalProject: Path): Boolean

    /**
     * Apply Spotless to a single file **without writing to disk**
     *
     * do not window the input for bulk processing, implement [applyToMultiple] instead
     *
     * see [Spotless IDE_HOOK](https://github.com/diffplug/spotless/blob/main/plugin-gradle/IDE_HOOK.md)
     *
     * @param externalProject The root path of the external project
     * @param absolutePath The absolute path of the file to format
     * @param fileContent The current content of the file, which may be unsaved changes
     *
     */
    public suspend fun applyToSingle(
        externalProject: Path,
        absolutePath: Path,
        fileContent: String,
    ): SpotlessSingleFileResult

    public suspend fun applyToProject(
        externalProject: Path,
    )

}


public sealed interface SpotlessSingleFileResult {
    /**
     * Formatted successfully with the file on disk untouched
     * @property content The formatted output
     */
    public data class Dirty(public val content: String) : SpotlessSingleFileResult

    /**
     * Untouched as already formatted
     */
    public object Clean : SpotlessSingleFileResult

    /**
     * Not covered by Spotless, either no formater for the filetype or path pattern not included
     */
    public object NotCovered : SpotlessSingleFileResult
    public interface Failed : SpotlessSingleFileResult {
        /**
         * Description or stacktrace of the failure
         */
        public val message: String
    }

    /**
     * The formatter did not converge after multiple attempts
     *
     * Result loops in `A -> B -> A -> B -> ...` e.g.
     */
    public data class DidNotConverge(override val message: String) : Failed

    /**
     * Error occurred during formatting, see `message` for details
     */
    public data class Error(override val message: String) : Failed

    public companion object {
        /**
         * Parse the output of a Spotless CLI invocation for a single file
         *
         * see [Spotless IDE_HOOK](https://github.com/diffplug/spotless/blob/main/plugin-gradle/IDE_HOOK.md)
         */
        public fun parse(stderr: String, stdout: String): SpotlessSingleFileResult {
            return when {
                stderr.isEmpty() -> NotCovered
                stderr.startsWith("IS DIRTY") -> Dirty(stdout)
                stderr.startsWith("IS CLEAN") -> Clean
                stderr.startsWith("DID NOT CONVERGE") -> DidNotConverge(stderr.removePrefix("DID NOT CONVERGE"))
                else -> Error(stderr)
            }
        }
    }
}
