/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.spotless

import com.intellij.psi.PsiFile

/**
 * Prepares formatter input for daemon steps that require IDE-specific handling.
 *
 * Implementations must not retain [PsiFile] instances received through [isApplicableTo] or [preprocess].
 */
public interface SpotlessFormattingPreprocessor {
    /** Returns whether this preprocessor can handle the actual formatting target. */
    public fun isApplicableTo(psiFile: PsiFile): Boolean

    /** Returns whether the daemon's configured steps require this preprocessor. */
    public fun isTriggeredBy(daemonSteps: List<String>): Boolean

    /** Returns the transformed request, or `null` when preprocessing is unavailable. */
    public suspend fun preprocess(
        request: SpotlessFormattingPreprocessRequest,
    ): SpotlessFormattingPreprocessResult?
}

public data class SpotlessFormattingPreprocessRequest(
    public val psiFile: PsiFile,
    public val content: CharSequence,
    public val daemonSteps: List<String>,
)

public data class SpotlessFormattingPreprocessResult(
    public val content: CharSequence,
    public val skippedSteps: Set<String> = emptySet(),
)
