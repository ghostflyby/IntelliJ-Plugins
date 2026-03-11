<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Mill Changelog

## [Unreleased]

### Added

- Initial Mill external system implementation with manager, settings, project resolver, and task manager support.
- Project Settings controls for executable path, Mill JVM options, metadata-backed import, and per-module task nodes.
- Basic Mill project detection, source root mapping, and default task nodes.
- Basic CLI-backed Mill task execution and auto-import file tracking.
- Best-effort multi-module import from Mill target resolution.
- Mill metadata-backed source, resource, and generated-root import where `show <module>.*` is available.
- Explicit test-to-production module dependencies for discovered `*.test` modules.
- Direct Mill inter-module dependency import from `show <module>.moduleDeps` where discovered modules can be matched.
- Best-effort aggregate Mill compile classpath import into `External Libraries`.
- Per-module task nodes for actionable resolved targets such as `compile`, `test`, and `runBackground`.
- Structured progress events for Mill project import.
- A Mill Project Settings page under `Build Tools`.
- Standard unlinked-project detection so Mill projects can surface the IDE import prompt after open.
- A Mill-specific external-system view contributor for cleaner task and dependency labels in the tool window.

### Changed

### Deprecated

### Removed

### Fixed

- Fixed Mill local settings state initialization so project save no longer fails on the local settings component.
- Added a Mill project-open processor so Mill project directories participate in the IDE project-open flow instead of
  only manual external-system linking.

### Security
