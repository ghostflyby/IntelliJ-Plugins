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
import com.intellij.util.disposeOnCompletion
import kotlinx.coroutines.CoroutineScope
import java.util.*
import kotlin.reflect.KProperty

public class AutoCleanKey<K : Key<T>, T> private constructor(public val key: K) {
    public constructor(disposable: Disposable, key: K) : this(key) {
        Disposer.register(disposable) {
            synchronized(holders) {
                holders.forEach {
                    it.removeUserData(this.key)
                }
            }
        }
    }

    public constructor(scope: CoroutineScope, key: K) : this(
        Disposer.newDisposable().apply {
            disposeOnCompletion(scope)
        },
        key,
    )


    private val holders: MutableSet<UserDataHolder> = Collections.synchronizedSet(
        Collections.newSetFromMap(
            WeakHashMap(),
        ),
    )

    internal fun put(holder: UserDataHolder) {
        holders.add(holder)
    }
}

public operator fun <H : UserDataHolder, K : Key<T>, T> AutoCleanKey<K, T>.getValue(
    thisRef: H,
    property: KProperty<*>,
): T? {
    return property.run { thisRef.getUserData(key) }
}

public operator fun <H : UserDataHolder, K : Key<T>, T> AutoCleanKey<K, T>.setValue(
    thisRef: H,
    property: KProperty<*>,
    value: T?,
) {
    put(thisRef)
    property.run { thisRef.putUserData(key, value) }
}

@JvmName("getValueFromKeyWithDefaultValue")
public operator fun <H : UserDataHolder, K : KeyWithDefaultValue<T>, T> AutoCleanKey<K, T>.getValue(
    thisRef: H,
    property: KProperty<*>,
): T {
    return property.run { thisRef.getUserData(key) ?: key.defaultValue }
}

@JvmName("setValueFromNotNullLazyKey")
public operator fun <H : UserDataHolder, K : NotNullLazyKey<T, H>, T> AutoCleanKey<K, T>.getValue(
    thisRef: H,
    property: KProperty<*>,
): T {
    return property.run { key.getValue(thisRef) }
}

public fun <K : Key<T>, T> K.toAutoCleanKey(disposable: Disposable): AutoCleanKey<K, T> {
    return AutoCleanKey(disposable, this)
}

public fun <K : Key<T>, T> K.toAutoCleanKey(scope: CoroutineScope): AutoCleanKey<K, T> {
    return AutoCleanKey(scope, this)
}
