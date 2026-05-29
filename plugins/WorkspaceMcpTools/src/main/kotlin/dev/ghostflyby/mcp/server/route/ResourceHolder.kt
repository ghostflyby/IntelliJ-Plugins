/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.server.route

import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties

/**
 * Stores deserialized @Resource route objects for typed access from handlers.
 *
 * When a parameterized route matches, the deserialized typed tree is stored
 * here. For example, matching `/projects/my-project/files/src/Foo.kt` produces
 * a [dev.ghostflyby.mcp.server.route.resources.ProjectFileResource] whose parent is a [dev.ghostflyby.mcp.server.route.resources.ProjectResource]. Both are stored
 * so that `resourceHolder.get<ProjectResource>()?.projectKey` works.
 *
 * Handlers access resources via [get], and [call.project()] uses this to
 * look up the project key without going through [dev.ghostflyby.mcp.server.route.Keys.RouteParameters].
 */
internal class ResourceHolder {
    private val map = linkedMapOf<KClass<*>, Any>()

    fun <T : Any> put(value: T) {
        map[value::class] = value
        putParentResources(value)
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(type: KClass<T>): T? = map[type] as? T

    inline fun <reified T : Any> get(): T? = get(T::class)

    private fun putParentResources(value: Any) {
        for (prop in value::class.memberProperties) {
            @Suppress("UNCHECKED_CAST")
            val parentValue = (prop as KProperty1<Any, Any?>).getter.call(value) ?: continue
            val kclass = parentValue::class
            // Only recurse into @Resource-annotated types (route parents), not primitives/collections
            if (kclass.java.isPrimitive || kclass.java.name.startsWith("kotlin.")) continue
            if (kclass !in map) {
                map[kclass] = parentValue
                putParentResources(parentValue)
            }
        }
    }
}
