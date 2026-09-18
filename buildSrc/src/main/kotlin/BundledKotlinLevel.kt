/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

import org.gradle.api.GradleException
import org.gradle.api.provider.ProviderFactory
import org.jetbrains.intellij.platform.gradle.utils.PlatformKotlinVersions
import org.jetbrains.intellij.platform.gradle.utils.Version
import org.jetbrains.kotlin.gradle.dsl.KotlinCommonCompilerOptions
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

/**
 * Kotlin level to compile the sources at.
 *
 * The Kotlin runtime comes from the IDE, not from Gradle, so compiling against a newer Kotlin than
 * the IDE ships lets code build and then fail at runtime with `NoSuchMethodError`. The level
 * therefore follows the Kotlin bundled with the oldest supported platform build
 * ([BuildLogicProperties.PLUGIN_SINCE_BUILD]), not the compile-target platform, which may be newer.
 *
 * The platform-to-Kotlin mapping is the one published by the IntelliJ Platform Gradle Plugin, so
 * updating a platform property is enough: there is no second place to keep in sync.
 */
object BundledKotlinLevel {

    /** Kotlin version bundled with the platform of [sinceBuild], for example `2.3.10` for build 261. */
    fun bundledFor(sinceBuild: Int): Version =
        PlatformKotlinVersions.entries.firstOrNull { Version(sinceBuild) >= it.key }?.value
            ?: throw GradleException(
                "No bundled Kotlin version is known for IntelliJ Platform build $sinceBuild; " +
                    "the platform defines one for builds ${PlatformKotlinVersions.keys.joinToString()}.",
            )

    /**
     * Compiler level for the platform of [sinceBuild], for example `KOTLIN_2_3`.
     *
     * `apiVersion` is what keeps the code loadable on the standard library of the oldest supported
     * platform; `languageVersion` is pinned to the same level because Kotlin requires the API version
     * not to exceed the language version and recommends the two to match.
     */
    fun forSinceBuild(sinceBuild: Int): KotlinVersion {
        val bundled = bundledFor(sinceBuild)
        val level = "${bundled.major}.${bundled.minor}"
        return runCatching { KotlinVersion.fromVersion(level) }
            .getOrElse { failure ->
                throw GradleException(
                    "IntelliJ Platform build $sinceBuild bundles Kotlin $level, which the Kotlin Gradle plugin " +
                        "in use does not support; raise the `kotlin` version in gradle/libs.versions.toml.",
                    failure,
                )
            }
    }
}

/**
 * Pins both compiler options to the level of [BundledKotlinLevel], for projects that compile Kotlin
 * but do not apply the `repo.module` conventions.
 */
fun KotlinCommonCompilerOptions.configureBundledKotlinLevel(providers: ProviderFactory) {
    val level = providers.gradleProperty(BuildLogicProperties.PLUGIN_SINCE_BUILD)
        .map { sinceBuild -> BundledKotlinLevel.forSinceBuild(sinceBuild.toInt()) }
    languageVersion.set(level)
    apiVersion.set(level)
}
