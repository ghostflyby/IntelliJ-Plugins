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
 * Implementations must not retain [PsiFile] instances received through [isApplicableTo] or
 * [SpotlessFormattingPreprocessContext.psiFile].
 */
public interface SpotlessFormattingPreprocessor {
    /** Returns whether this preprocessor can handle the actual formatting target. */
    public fun isApplicableTo(psiFile: PsiFile): Boolean

    /** Returns the transformed request, or `null` when preprocessing is unavailable. */
    public suspend fun preprocess(
        context: SpotlessFormattingPreprocessContext,
    ): SpotlessFormattingPreprocessResult?
}

/** Invocation-scoped formatter input supplied by the core. */
public interface SpotlessFormattingPreprocessContext {
    public val psiFile: PsiFile
    public val content: CharSequence
    public val daemonSteps: List<String>
}

public class SpotlessFormattingPreprocessResult(
    public val content: CharSequence,
    skippedSteps: Set<String> = emptySet(),
) {
    public val skippedSteps: Set<String> = skippedSteps.toSet()
}
