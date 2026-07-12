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

### Deprecated

### Removed

### Fixed

- Avoid blocking dynamic plugin unload by reusing Gradle-owned Workspace Model
  entity sources instead of plugin-owned sources.
- Restored Groovy DSL goto declaration for custom version catalog roots such as
  `customLibs` in `buildSrc` convention plugins.

### Security

[Unreleased]: https://github.com/ghostflyby/IntelliJ-Plugins/commits/HEAD/plugins/GradleTypesafeConventions
