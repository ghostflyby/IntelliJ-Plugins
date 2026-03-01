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

package dev.ghostflyby.intellij

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DisposableKeyTest {

    @Test
    fun removesUserDataOnDispose() {
        val disposable: Disposable = Disposer.newDisposable()
        val key = Key.create<String>("key.with.clean.test")
        val cleaner = DisposableKey(disposable, key)

        val holder = object : UserDataHolderBase() {
            var value: String? by cleaner
        }

        holder.value = "kept"

        assertEquals("kept", holder.value)

        Disposer.dispose(disposable)

        assertNull(holder.getUserData(key))
    }

    @Test
    fun removesUserDataFromAllHoldersOnDispose() {
        val disposable: Disposable = Disposer.newDisposable()
        val key = Key.create<String>("key.with.clean.multiple")
        val cleaner = DisposableKey(disposable, key)

        val holderA = object : UserDataHolderBase() {
            var value: String? by cleaner
        }
        val holderB = object : UserDataHolderBase() {
            var value: String? by cleaner
        }

        holderA.value = "kept-a"
        holderB.value = "kept-b"

        Disposer.dispose(disposable)

        assertNull(holderA.getUserData(key))
        assertNull(holderB.getUserData(key))
    }

    @Test
    fun returnsDefaultValueWhenUnset() {
        val disposable: Disposable = Disposer.newDisposable()
        val key = KeyWithDefaultValue.create("key.with.clean.default", "fallback")
        val cleaner = DisposableKey(disposable, key)

        val holder = object : UserDataHolderBase() {
            val value: String by cleaner
        }

        assertEquals("fallback", holder.value)

        Disposer.dispose(disposable)
    }

    @Test
    fun returnsStoredValueWhenPresent() {
        val disposable: Disposable = Disposer.newDisposable()
        val key = KeyWithDefaultValue.create("key.with.clean.stored", "fallback")
        val cleaner = DisposableKey(disposable, key)

        val holder = object : UserDataHolderBase() {
            var value: String by cleaner
        }

        holder.value = "stored"

        assertEquals("stored", holder.value)

        Disposer.dispose(disposable)
    }

    @Test
    fun cleansUpWhenScopeCompletes() {
        val job = Job()
        val scope = CoroutineScope(job)
        val key = Key.create<String>("key.with.clean.scope")
        val cleaner = DisposableKey(scope, key)

        val holder = object : UserDataHolderBase() {
            var value: String? by cleaner
        }

        holder.value = "scoped"

        runBlocking {
            job.complete()
            job.join()
        }

        assertNull(holder.getUserData(key))
    }

    @Test
    fun notNullLazyKeyUsesComputedValueAndCachesIt() {
        val disposable: Disposable = Disposer.newDisposable()
        val key = NotNullLazyKey.createLazyKey<String, UserDataHolderBase>("key.with.clean.lazy") {
            "computed"
        }
        val cleaner = DisposableKey(disposable, key)

        val holder = object : UserDataHolderBase() {
            val value: String by cleaner
        }

        assertEquals("computed", holder.value)

        Disposer.dispose(disposable)
    }
}
