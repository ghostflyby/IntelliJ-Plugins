<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Spotless Integration IntelliJ Plugin Changelog

## [Unreleased]

### Added

- optimize Java imports before formatting when the Spotless configuration expands or forbids wildcard imports.
- show a provider-neutral Spotless status bar widget with per-external-project daemon status, restart, and stop
  controls.

### Changed

- redesigned the public daemon-provider and formatting-preprocessor extension contracts around file-specific target
  resolution, project-scoped state flows, core-owned invocation contexts, and connection-only daemon endpoints.
- restart active Gradle Spotless daemons after every Gradle project synchronization or daemon configuration change.

### Deprecated

### Removed

### Fixed

### Security

## [1.1.0] - 2026-03-22

### Changed

- refined the config UI layout [#184](https://github.com/ghostflyby/IntelliJ-Plugins/pull/184)
- documented plugin API [#184](https://github.com/ghostflyby/IntelliJ-Plugins/pull/184)

## [1.0.0] - 2026-03-02

### Added

- exposed Spotless as an explicit application service API via plugin.xml interface + implementation
  registration.

### Changed

- redesigned Spotless daemon cleanup lifecycle: removed blocking `Disposable`-based shutdown from daemon/provider APIs,
  centralized async release in `Spotless` service, and aligned Gradle daemon cleanup with the new lifecycle.
- switched daemon stop to a fixed core HTTP contract and replaced provider stop customization with an optional post-stop
  notification hook.

## [0.4.0] - 2025-12-20

### Changed

- update default Spotless Gradle daemon version to 0.5.4 [#133](https://github.com/ghostflyby/IntelliJ-Plugins/pull/133)

## [0.3.0] - 2025-12-19

### Added

- update default Spotless Gradle daemon version to 0.5.1 [#131](https://github.com/ghostflyby/IntelliJ-Plugins/pull/131)

## [0.2.0] - 2025-12-18

### Added

- configuration options for Spotless Gradle daemon version and
  jar [#127](https://github.com/ghostflyby/IntelliJ-Plugins/pull/127)

## [0.1.1] - 2025-12-12

### Added

- only run daemon task in rootProject [#118](https://github.com/ghostflyby/IntelliJ-Plugins/pull/118)

### Fixed

- fix: no longer timeout when a file is unchanged [#120](https://github.com/ghostflyby/IntelliJ-Plugins/pull/120)

## [0.0.1] - 2025-12-12

### Added

- Initial release of the Spotless Integration plugin for IntelliJ IDEA.
- Support for automatic code formatting using Spotless.

[Unreleased]: https://github.com/ghostflyby/IntelliJ-Plugins/compare/SpotlessIntegration-v1.1.0...HEAD
[1.1.0]: https://github.com/ghostflyby/IntelliJ-Plugins/compare/SpotlessIntegration-v1.0.0...SpotlessIntegration-v1.1.0
[1.0.0]: https://github.com/ghostflyby/IntelliJ-Plugins/compare/SpotlessIntegration-v0.4.0...SpotlessIntegration-v1.0.0
[0.4.0]: https://github.com/ghostflyby/IntelliJ-Plugins/compare/SpotlessIntegration-v0.3.0...SpotlessIntegration-v0.4.0
[0.3.0]: https://github.com/ghostflyby/IntelliJ-Plugins/compare/SpotlessIntegration-v0.2.0...SpotlessIntegration-v0.3.0
[0.2.0]: https://github.com/ghostflyby/IntelliJ-Plugins/compare/SpotlessIntegration-v0.1.1...SpotlessIntegration-v0.2.0
[0.1.1]: https://github.com/ghostflyby/IntelliJ-Plugins/compare/SpotlessIntegration-v0.0.1...SpotlessIntegration-v0.1.1
[0.0.1]: https://github.com/ghostflyby/IntelliJ-Plugins/commits/SpotlessIntegration-v0.0.1
