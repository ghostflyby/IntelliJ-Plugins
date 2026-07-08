/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.intellij

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URLClassLoader

internal class PluginInfoTest {
    @Test
    fun rejectsPlainClassLoader() {
        URLClassLoader(emptyArray(), null).use { classLoader ->
            val info = PluginInfoProvider(classLoader)

            val error = assertThrows(IllegalArgumentException::class.java) {
                info.id
            }

            assertTrue(error.message.orEmpty().contains("PluginAwareClassLoader"))
        }
    }
}
