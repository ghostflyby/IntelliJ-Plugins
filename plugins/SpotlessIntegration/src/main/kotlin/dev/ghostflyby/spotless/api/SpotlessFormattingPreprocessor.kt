/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.spotless.api

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.psi.PsiFile
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.jetbrains.annotations.ApiStatus

/**
 * Prepares formatter input for daemon steps that require IDE-specific handling.
 *
 * Implementations must not retain [PsiFile] instances received through [isApplicableTo] or
 * [Context.psiFile].
 */
@ApiStatus.OverrideOnly
public interface SpotlessFormattingPreprocessor {
    public companion object {
        @JvmField
        public val EP_NAME: ExtensionPointName<SpotlessFormattingPreprocessor> =
            ExtensionPointName.create("dev.ghostflyby.spotless.spotlessFormattingPreprocessor")
    }

    /** Returns whether this preprocessor can handle the actual formatting target. */
    @RequiresReadLock
    public fun isApplicableTo(psiFile: PsiFile): Boolean

    /** Returns the transformed request, or `null` when preprocessing is unavailable. */
    public suspend fun preprocess(
        context: Context,
    ): Result?

    /** Invocation-scoped formatter input supplied by the core. */
    public interface Context {
        public val psiFile: PsiFile
        public val content: CharSequence
        public val daemonSteps: List<String>
    }

    public class Result(
        public val content: CharSequence,
        public val skippedSteps: Set<String> = emptySet(),
    )
}
