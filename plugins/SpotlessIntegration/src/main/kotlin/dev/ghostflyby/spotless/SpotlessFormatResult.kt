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

package dev.ghostflyby.spotless

public sealed interface SpotlessFormatResult {
    /**
     * Formatted successfully with the file on disk untouched
     * @property content The formatted output
     */
    public data class Dirty(val content: String) : SpotlessFormatResult

    /**
     * Untouched as already formatted
     */
    public object Clean : SpotlessFormatResult

    /**
     * Not covered by Spotless, either no formater for the filetype or path pattern not included
     */
    public object NotCovered : SpotlessFormatResult

    /**
     * Error occurred during formatting, see `message` for details
     */
    public data class Error(val message: String) : SpotlessFormatResult


}