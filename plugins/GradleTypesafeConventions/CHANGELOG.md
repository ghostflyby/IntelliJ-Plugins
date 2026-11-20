<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Gradle Typesafe Conventions Changelog

## [Unreleased]

## [0.1.0] - 2026-09-19

### Added

- Initial integration for Gradle
  [`dev.panuszewski.typesafe-conventions`](https://github.com/radoslaw-panuszewski/typesafe-conventions-gradle-plugin)
  version catalog models.
- Support for build logic version catalog navigation and usage search in builds
  that apply `dev.panuszewski.typesafe-conventions`.

### Changed

- Reduced Kotlin catalog navigation and Find Usages overhead by reusing synchronized catalog and TOML alias indexes and
  limiting indexed usage searches to the Gradle builds that consume the target catalog.

### Fixed

- Ignore unrelated `Project` extensions that resemble generated catalog accessors, completely clean up every unlinked
  Gradle root, and refresh synchronized catalogs without blocking the UI thread.
- Fixed an IntelliJ IDEA 2026.1 Kotlin DSL navigation crash while preserving declaration navigation, Find Usages, and
  rename support for version catalogs used from `buildSrc` and included build logic.
- Resolve dotted and separator-normalized Kotlin catalog accessors to the exact TOML key segment, so Goto Declaration,
  Find Usages, and rename preserve the unaffected parts of an alias and ignore shadowed or programmatic-only accessors.
- Goto Declaration on the `versions`, `bundles`, and `plugins` token of a Kotlin catalog accessor now reaches the
  matching TOML section instead of falling through to the generated accessor code.
- Find Usages on a `versions`, `bundles`, or `plugins` section name in a version catalog now finds the Kotlin
  accessors using that section, including usages in `buildSrc` and included build logic, and reports usages of every
  catalog even when several catalogs are searched together.
- Preserve catalog navigation across sequential linked Gradle root syncs, failed or cancelled imports, IDE restarts, and
  unlink operations without replacing last-known-good state with partial model data.
- Avoid blocking dynamic plugin unload by reusing Gradle-owned Workspace Model
  entity sources instead of plugin-owned sources.
- Restored Groovy DSL goto declaration for custom version catalog roots such as
  `customLibs` in `buildSrc` convention plugins.
- Resolve Groovy catalog accessors declared through standard tables, dotted keys, or inline tables with separator
  normalization, while keeping same-named accessors isolated by catalog section.
- Restore Kotlin Goto Declaration and Find Usages for generated catalog entrypoints outside the hard-coded
  generated-source path, including version catalog aliases used in precompiled Kotlin script `plugins` blocks.
- Restore catalog navigation after Gradle sync when version catalog models are reported by `buildSrc` or another
  resolver beneath the linked Gradle root.

[Unreleased]: https://github.com/ghostflyby/IntelliJ-Plugins/compare/GradleTypesafeConventions-v0.1.0...HEAD
[0.1.0]: https://github.com/ghostflyby/IntelliJ-Plugins/commits/GradleTypesafeConventions-v0.1.0
