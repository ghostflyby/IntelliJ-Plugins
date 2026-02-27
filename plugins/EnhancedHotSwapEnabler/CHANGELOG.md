<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# EnhancedHotSwapEnabler Changelog

## [Unreleased]

### Added

- bundle `org.hotswapagent:hotswap-agent` into plugin sandbox/package via dedicated
  distribution configuration and version-catalog managed dependency
- add third-party notice for bundled HotSwapAgent (GPL-2.0) with source retrieval location

### Changed

- make HotSwap config resolution explicit via shared `resolveHotSwapConfig(...)` logic and
  add dedicated resolution tests
- refactor module layout: split `common` and `gradle` source sets into nested Gradle
  subprojects with minimal dependencies
- update Gradle init script classpath assembly to include both plugin and shared-common jars
  after subproject split
- make bundled HotSwapAgent path resolution a direct top-level lazy property lookup
  (no service/resolver indirection)

### Deprecated

### Removed

- remove obsolete HotSwapAgent download/warm-up text entries from localization bundles
- remove now-unneeded agent resolver/test scaffolding after switching to required bundled artifact

### Fixed

- simplify HotSwapAgent resolution to packaged-jar lookup, removing runtime download path
- tighten `HotswapRunConfigurationExtension` cleanup by tracking `UserDataHolder`s and clearing
  state on dispose

### Security

## [1.3.7] - 2026-02-24

### Fixed

- add Java-version-aware HotSwapAgent `--add-opens` JVM args for both RunConfiguration and Gradle
  `JavaExec` [#143](https://github.com/ghostflyby/IntelliJ-Plugins/issues/143)

## [1.3.6] - 2025-11-22

### Fixed

- downgrade hotswap Gradle plugin jvm target 1.8 for old Gradle
  capability [#99](https://github.com/ghostflyby/IntelliJ-Plugins/pull/99)

## [1.3.5] - 2025-11-03

### Added

- Support Gradle running on Java 17 [#90](https://github.com/ghostflyby/IntelliJ-Plugins/pull/90)

## [1.2.5] - 2025-08-31

### Fixed

- check `None` DCEVM Support case before enabling in
  Gradle [#65](https://github.com/ghostflyby/IntelliJ-Plugins/pull/65)

## [1.2.4] - 2025-08-24

### Fixed

- move vmOptions check to doFirst to reuse
  configuration-cache [#54](https://github.com/ghostflyby/IntelliJ-Plugins/pull/54)

## [1.2.3] - 2025-08-21

### Changed

- make Gradle plugin a optional dependency [#48](https://github.com/ghostflyby/IntelliJ-Plugins/pull/48)

### Fixed

- UserData memory leak in RunConfigurationExtension [#49](https://github.com/ghostflyby/IntelliJ-Plugins/pull/49)

## [1.2.2] - 2025-08-20

### Fixed

- detect altjvm on different OSes [#45](https://github.com/ghostflyby/IntelliJ-Plugins/pull/45)
- check dcevm on java 1.8 with proper flags [#46](https://github.com/ghostflyby/IntelliJ-Plugins/pull/46)

## [1.2.0] - 2025-08-19

### Fixed

- configuration cache problem [#43](https://github.com/ghostflyby/IntelliJ-Plugins/issues/43)

### Added

- Auto download and configure the HotSwapAgent [#39](https://github.com/ghostflyby/IntelliJ-Plugins/pull/39)

## [1.1.0] - 2025-08-19

### Added

- Configurations and settings supports

## [1.0.0] - 2025-08-16

### Added

- Initial scaffold

### Fixed

- Not editing parameters for non-debug starts
- Wrong Gradle plugin class name

[Unreleased]: https://github.com/ghostflyby/IntelliJ-Plugins/compare/EnhancedHotSwapEnabler-v1.3.7...HEAD
[1.3.7]: https://github.com/ghostflyby/IntelliJ-Plugins/compare/EnhancedHotSwapEnabler-v1.3.6...EnhancedHotSwapEnabler-v1.3.7
[1.3.6]: https://github.com/ghostflyby/IntelliJ-Plugins/compare/EnhancedHotSwapEnabler-v1.3.5...EnhancedHotSwapEnabler-v1.3.6
[1.3.5]: https://github.com/ghostflyby/IntelliJ-Plugins/compare/EnhancedHotSwapEnabler-v1.2.5...EnhancedHotSwapEnabler-v1.3.5
[1.2.5]: https://github.com/ghostflyby/IntelliJ-Plugins/compare/EnhancedHotSwapEnabler-v1.2.4...EnhancedHotSwapEnabler-v1.2.5
[1.2.4]: https://github.com/ghostflyby/IntelliJ-Plugins/compare/EnhancedHotSwapEnabler-v1.2.3...EnhancedHotSwapEnabler-v1.2.4
[1.2.3]: https://github.com/ghostflyby/IntelliJ-Plugins/compare/EnhancedHotSwapEnabler-v1.2.2...EnhancedHotSwapEnabler-v1.2.3
[1.2.2]: https://github.com/ghostflyby/IntelliJ-Plugins/compare/EnhancedHotSwapEnabler-v1.2.0...EnhancedHotSwapEnabler-v1.2.2
[1.2.0]: https://github.com/ghostflyby/IntelliJ-Plugins/compare/EnhancedHotSwapEnabler-v1.1.0...EnhancedHotSwapEnabler-v1.2.0
[1.1.0]: https://github.com/ghostflyby/IntelliJ-Plugins/compare/EnhancedHotSwapEnabler-v1.0.0...EnhancedHotSwapEnabler-v1.1.0
[1.0.0]: https://github.com/ghostflyby/IntelliJ-Plugins/commits/EnhancedHotSwapEnabler-v1.0.0
