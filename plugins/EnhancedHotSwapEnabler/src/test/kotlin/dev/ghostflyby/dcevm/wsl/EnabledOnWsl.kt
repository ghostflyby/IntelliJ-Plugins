/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.dcevm.wsl

import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.extension.ConditionEvaluationResult
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.ExecutionCondition
import org.junit.jupiter.api.extension.ExtensionContext

/**
 * Enables a test class or method only on a Windows host with at least one WSL distribution
 * installed — discovered from the `\\wsl.localhost` UNC root, with no configuration switch.
 *
 * Composed from JUnit's `@EnabledOnOs` plus a UNC-root availability condition, so WSL tests sit
 * in the standard JUnit enabling family next to future Windows-native (`@EnabledOnOs(WINDOWS)`)
 * tests.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@EnabledOnOs(OS.WINDOWS)
@ExtendWith(WslAvailableCondition::class)
internal annotation class EnabledOnWsl

internal class WslAvailableCondition : ExecutionCondition {

    // JUnit Jupiter 6 renamed ExecutionCondition.evaluate to evaluateExecutionCondition
    override fun evaluateExecutionCondition(context: ExtensionContext): ConditionEvaluationResult {
        val distributions = WslTestEnvironment.installedDistributions()
        return if (distributions.isEmpty()) {
            ConditionEvaluationResult.disabled("No WSL distribution found under \\\\wsl.localhost")
        } else {
            ConditionEvaluationResult.enabled("WSL distributions: ${distributions.joinToString()}")
        }
    }
}
