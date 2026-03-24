<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# MacOSRecents Changelog

## [Unreleased]

### Changed

- Refactored recents synchronization to use diff-based updates with debounced scheduling.
- Added a dedicated Cocoa bridge/sync service with error isolation for startup and update flows.
- Added tests for ordering, deduplication, incremental updates, and startup merge behavior.

## [1.0.0] - 2025-08-26

### Added

- Initial implementation of the MacOSRecents plugin.

[Unreleased]: https://github.com/ghostflyby/IntelliJ-Plugins/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/ghostflyby/IntelliJ-Plugins/commits/v1.0.0
