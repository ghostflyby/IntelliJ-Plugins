<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# MacOSRecents Changelog

## [Unreleased]

## [1.1.1] - 2026-11-22

### Added

- IDE errors are now reported to JetBrains Marketplace.

## [1.1.0] - 2026-03-24

### Changed

- Refactored recents synchronization to use diff-based updates with debounced scheduling.
- Added a dedicated Cocoa bridge/sync service with error isolation for startup and update flows.
- Added tests for ordering, deduplication, incremental updates, and startup merge behavior.

## [1.0.0] - 2025-08-26

### Added

- Initial implementation of the MacOSRecents plugin.

[Unreleased]: https://github.com/ghostflyby/IntelliJ-Plugins/compare/macOSRecents-v1.1.1...HEAD
[1.1.1]: https://github.com/ghostflyby/IntelliJ-Plugins/compare/macOSRecents-v1.1.0...macOSRecents-v1.1.1
[1.1.0]: https://github.com/ghostflyby/IntelliJ-Plugins/compare/macOSRecents-v1.0.0...macOSRecents-v1.1.0
[1.0.0]: https://github.com/ghostflyby/IntelliJ-Plugins/commits/macOSRecents-v1.0.0
