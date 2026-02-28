/*
 * Copyright (c) 2025-2026 ghostflyby
 * SPDX-FileCopyrightText: 2025-2026 ghostflyby
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

import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.provider.ProviderFactory
import org.gradle.kotlin.dsl.property
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import javax.inject.Inject

abstract class BuildLogicSettings @Inject constructor(objects: ObjectFactory, providers: ProviderFactory) {
    val platformType: Property<IntelliJPlatformType> = objects.property<IntelliJPlatformType>()
        .convention(IntelliJPlatformType.IntellijIdea)
    val platformVersion: Property<String> = objects.property<String>()
        .convention(providers.gradleProperty("platformVersion"))
    val pluginVersion: Property<String> = objects.property()
    val pluginSinceBuild: Property<String> = objects.property<String>()
        .convention(providers.gradleProperty("pluginSinceBuild"))
}
