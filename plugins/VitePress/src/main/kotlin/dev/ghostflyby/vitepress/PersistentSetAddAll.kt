/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

// 2026.1 (build 261) bundles kotlinx-collections-immutable 0.4.0, which only provides
// PersistentSet.addAll/removeAll; starting with 2026.2 (build 262) the bundled version deprecates
// them in favor of addingAll/removingAll. The bridge below prefers the new names and falls back
// to the old ones, so the plugin links on both without deprecated API usage on newer runtimes.

package dev.ghostflyby.vitepress

import kotlinx.collections.immutable.PersistentSet
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType

private fun getHandle(old: String, new: String): MethodHandle {
    val lookup: MethodHandles.Lookup = MethodHandles.publicLookup()
    val cls = PersistentSet::class.java
    val type = MethodType.methodType(PersistentSet::class.java, Collection::class.java)
    return runCatching {
        lookup.findVirtual(cls, new, type)
    }.recoverCatching {
        lookup.findVirtual(cls, old, type)
    }.getOrElse { e ->
        throw NoSuchMethodError(
            "Neither PersistentSet.addingAll(Collection) nor PersistentSet.addAll(Collection) exists",
        ).also { it.initCause(e) }
    }
}

private val addAllHandle: MethodHandle = getHandle("addAll", "addingAll")

private val removeAllHandle: MethodHandle = getHandle("removeAll", "removingAll")


@Suppress("UNCHECKED_CAST")
internal fun <E> PersistentSet<E>.addAllBridge(elements: Collection<E>): PersistentSet<E> {
    return addAllHandle.invoke(this, elements) as PersistentSet<E>
}

@Suppress("UNCHECKED_CAST")
internal fun <E> PersistentSet<E>.removeAllBridge(elements: Collection<E>): PersistentSet<E> {
    return removeAllHandle.invoke(this, elements) as PersistentSet<E>
}
