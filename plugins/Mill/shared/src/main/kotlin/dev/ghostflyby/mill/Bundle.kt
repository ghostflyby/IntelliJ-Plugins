/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mill

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey
import java.util.function.Supplier

private const val BUNDLE: String = "messages.Bundle"

private val bundle = DynamicBundle(Bundle::class.java, BUNDLE)

public object Bundle {
    @JvmStatic
    public fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): @Nls String =
        bundle.getMessage(key, *params)

    @JvmStatic
    @Nls
    public fun messagePointer(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): @Nls Supplier<String> =
        bundle.getLazyMessage(key, *params)
}
