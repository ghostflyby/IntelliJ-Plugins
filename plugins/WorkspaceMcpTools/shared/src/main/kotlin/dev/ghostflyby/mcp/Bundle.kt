/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey

private const val BUNDLE = "messages.Bundle"

// The bundle resources and this accessor live in the shared content module, so both the frontend
// and the backend module resolve message keys through their parent classloader.
private object BundleHolder {
    val bundle = DynamicBundle(BundleHolder::class.java, BUNDLE)
}

public fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): @Nls String =
    BundleHolder.bundle.getMessage(key, *params)
