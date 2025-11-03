<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# EnhancedHotSwapEnabler Changelog

## [Unreleased]

## [1.3.5] - 2025-11-03

### Added

- Support gradle running on Java 17 [#90](https://github.com/ghostflyby/IntelliJ-Plugins/pull/90)

## [1.2.5] - 2025-08-31

### Fixed

- check `None` DCEVM Support case before enabling in
  gradle [#65](https://github.com/ghostflyby/IntelliJ-Plugins/pull/65)

## [1.2.4] - 2025-08-24

### Fixed

- move vmOptions check to doFirst to reuse
  configuration-cache [#54](https://github.com/ghostflyby/IntelliJ-Plugins/pull/54)

## [1.2.3] - 2025-08-21

### Changed

- make gradle plugin a optional dependency [#48](https://github.com/ghostflyby/IntelliJ-Plugins/pull/48)

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
- Wrong gradle plugin class name

[Unreleased]: https://github.com/ghostflyby/IntelliJ-Plugins/compare/v1.3.5...HEAD
[1.3.5]: https://github.com/ghostflyby/IntelliJ-Plugins/compare/v1.2.5...v1.3.5
[1.2.5]: https://github.com/ghostflyby/IntelliJ-Plugins/compare/v1.2.4...v1.2.5
[1.2.4]: https://github.com/ghostflyby/IntelliJ-Plugins/compare/v1.2.3...v1.2.4
[1.2.3]: https://github.com/ghostflyby/IntelliJ-Plugins/compare/v1.2.2...v1.2.3
[1.2.2]: https://github.com/ghostflyby/IntelliJ-Plugins/compare/v1.2.0...v1.2.2
[1.2.0]: https://github.com/ghostflyby/IntelliJ-Plugins/compare/v1.1.0...v1.2.0
[1.1.0]: https://github.com/ghostflyby/IntelliJ-Plugins/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/ghostflyby/IntelliJ-Plugins/commits/v1.0.0
