/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

plugins {
    id("repo.intellij-module")
    `java-library`
    id("rpc")
    alias(libs.plugins.kotlin.serialization)
}

// The composed jar is named after the module id by the repo.intellij-module convention.
