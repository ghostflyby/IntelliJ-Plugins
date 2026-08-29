# Typesafe Conventions Integration

<!-- Plugin description -->
Integration for Gradle builds that use
[`dev.panuszewski.typesafe-conventions`](https://github.com/radoslaw-panuszewski/typesafe-conventions-gradle-plugin).

The Gradle plugin generates type-safe catalog accessors for convention plugins
in places such as `buildSrc` and included build logic. This IntelliJ plugin
adds the missing imported version catalog model for those builds so IDE
navigation and usage search can connect generated accessors back to their
original TOML entries.

## Features

- Supports version catalogs imported by the
  [`typesafe-conventions` Gradle plugin](https://github.com/radoslaw-panuszewski/typesafe-conventions-gradle-plugin)
  after Gradle sync.
- Restores Kotlin DSL navigation from catalog accessors such as
  `libs.junit.jupiter` to `gradle/libs.versions.toml`.
- Restores Groovy DSL navigation for catalog roots, including custom catalog
  names such as `customLibs`.
- Adds catalog models for build logic projects where IntelliJ IDEA does not
  create them by default, including `buildSrc`.

The integration is only active for Gradle builds whose settings apply
`dev.panuszewski.typesafe-conventions`.
<!-- Plugin description end -->
