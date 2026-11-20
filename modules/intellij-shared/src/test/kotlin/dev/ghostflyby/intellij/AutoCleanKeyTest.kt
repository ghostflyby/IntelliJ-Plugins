/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.intellij

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class AutoCleanKeyTest {

    @Test
    fun removesUserDataOnDispose() {
        val disposable: Disposable = Disposer.newDisposable()
        val key = Key.create<String>("key.with.clean.test")
        val cleaner = AutoCleanKey(disposable, key)

        val holder = object : UserDataHolderBase() {
            var value: String? by cleaner
        }

        holder.value = "kept"

        Assertions.assertEquals("kept", holder.value)

        Disposer.dispose(disposable)

        Assertions.assertNull(holder.getUserData(key))
    }

    @Test
    fun removesUserDataFromAllHoldersOnDispose() {
        val disposable: Disposable = Disposer.newDisposable()
        val key = Key.create<String>("key.with.clean.multiple")
        val cleaner = AutoCleanKey(disposable, key)

        val holderA = object : UserDataHolderBase() {
            var value: String? by cleaner
        }
        val holderB = object : UserDataHolderBase() {
            var value: String? by cleaner
        }

        holderA.value = "kept-a"
        holderB.value = "kept-b"

        Disposer.dispose(disposable)

        Assertions.assertNull(holderA.getUserData(key))
        Assertions.assertNull(holderB.getUserData(key))
    }

    @Test
    fun returnsDefaultValueWhenUnset() {
        val disposable: Disposable = Disposer.newDisposable()
        val key = KeyWithDefaultValue.create("key.with.clean.default", "fallback")
        val cleaner = AutoCleanKey(disposable, key)

        val holder = object : UserDataHolderBase() {
            val value: String by cleaner
        }

        Assertions.assertEquals("fallback", holder.value)

        Disposer.dispose(disposable)
    }

    @Test
    fun returnsStoredValueWhenPresent() {
        val disposable: Disposable = Disposer.newDisposable()
        val key = KeyWithDefaultValue.create("key.with.clean.stored", "fallback")
        val cleaner = AutoCleanKey(disposable, key)

        val holder = object : UserDataHolderBase() {
            var value: String by cleaner
        }

        holder.value = "stored"

        Assertions.assertEquals("stored", holder.value)

        Disposer.dispose(disposable)
    }

    @Test
    fun cleansUpWhenScopeCompletes() {
        val job = Job()
        val scope = CoroutineScope(job)
        val key = Key.create<String>("key.with.clean.scope")
        val cleaner = AutoCleanKey(scope, key)

        val holder = object : UserDataHolderBase() {
            var value: String? by cleaner
        }

        holder.value = "scoped"

        runBlocking {
            job.complete()
            job.join()
        }

        Assertions.assertNull(holder.getUserData(key))
    }

    @Test
    fun notNullLazyKeyUsesComputedValueAndCachesIt() {
        val disposable: Disposable = Disposer.newDisposable()
        val key = NotNullLazyKey.createLazyKey<String, UserDataHolderBase>("key.with.clean.lazy") {
            "computed"
        }
        val cleaner = AutoCleanKey(disposable, key)

        val holder = object : UserDataHolderBase() {
            val value: String by cleaner
        }

        Assertions.assertEquals("computed", holder.value)

        Disposer.dispose(disposable)
    }

    @Test
    fun toDisposableKeyWithDisposableCleansOnDispose() {
        val disposable: Disposable = Disposer.newDisposable()
        val key = Key.create<String>("key.toDisposableKey.disposable")
        val cleaner = key.toAutoCleanKey { disposable }

        val holder = object : UserDataHolderBase() {
            var value: String? by cleaner
        }

        holder.value = "stored"
        Assertions.assertEquals("stored", holder.value)

        Disposer.dispose(disposable)

        Assertions.assertNull(holder.getUserData(key))
    }

    @Test
    fun toDisposableKeyWithScopeCleansOnCompletion() {
        val job = Job()
        val scope = CoroutineScope(job)
        val key = Key.create<String>("key.toDisposableKey.scope")
        val cleaner = key.toAutoCleanKey(scope)

        val holder = object : UserDataHolderBase() {
            var value: String? by cleaner
        }

        holder.value = "scoped"
        Assertions.assertEquals("scoped", holder.value)

        runBlocking {
            job.complete()
            job.join()
        }

        Assertions.assertNull(holder.getUserData(key))
    }

    @Test
    fun toDisposableKeyWithProviderResolvesDisposableLazily() {
        val disposable: Disposable = Disposer.newDisposable()
        var providerCalled = false
        val key = Key.create<String>("key.toDisposableKey.provider")
        val cleaner =
            key.toAutoCleanKey {
                providerCalled = true
                disposable
            }

        Assertions.assertFalse(providerCalled)

        val holder = object : UserDataHolderBase() {
            var value: String? by cleaner
        }

        holder.value = "stored"

        Assertions.assertTrue(providerCalled)

        Disposer.dispose(disposable)

        Assertions.assertNull(holder.getUserData(key))
    }

    @Test
    fun toDisposableKeyExposesOriginalKey() {
        val disposable: Disposable = Disposer.newDisposable()
        val key = Key.create<String>("key.toDisposableKey.property")
        val cleaner = key.toAutoCleanKey { disposable }

        Assertions.assertEquals(key, cleaner.key)

        Disposer.dispose(disposable)
    }
}
