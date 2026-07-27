<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Gradle Typesafe Conventions Changelog

## [Unreleased]

### Added

- Initial integration for Gradle
  [`dev.panuszewski.typesafe-conventions`](https://github.com/radoslaw-panuszewski/typesafe-conventions-gradle-plugin)
  version catalog models.
- Support for build logic version catalog navigation and usage search in builds
  that apply `dev.panuszewski.typesafe-conventions`.

### Changed

- Reduced Kotlin catalog navigation and Find Usages overhead by reusing synchronized catalog and TOML alias indexes and
  limiting indexed usage searches to the Gradle builds that consume the target catalog.

### Deprecated

### Removed

### Fixed

- Fixed an IntelliJ IDEA 2026.1 Kotlin DSL navigation crash while preserving declaration navigation, Find Usages, and
  rename support for version catalogs used from `buildSrc` and included build logic.
- Resolve dotted and separator-normalized Kotlin catalog accessors to the exact TOML key segment, so Goto Declaration,
  Find Usages, and rename preserve the unaffected parts of an alias and ignore shadowed or programmatic-only accessors.
- Preserve catalog navigation across sequential linked Gradle root syncs, failed or cancelled imports, IDE restarts, and
  unlink operations without replacing last-known-good state with partial model data.
- Avoid blocking dynamic plugin unload by reusing Gradle-owned Workspace Model
  entity sources instead of plugin-owned sources.
- Restored Groovy DSL goto declaration for custom version catalog roots such as
  `customLibs` in `buildSrc` convention plugins.

### Security

[Unreleased]: https://github.com/ghostflyby/IntelliJ-Plugins/commits/HEAD/plugins/GradleTypesafeConventions
