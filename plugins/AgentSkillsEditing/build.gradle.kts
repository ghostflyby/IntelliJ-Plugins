/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

plugins {
    id("repo.intellij-plugin")
}

version = "1.0.0"

dependencies.intellijPlatform {
    bundledPlugin("com.intellij.modules.json")
    bundledPlugin("org.intellij.plugins.markdown")
    bundledPlugin("org.jetbrains.plugins.yaml")
}
