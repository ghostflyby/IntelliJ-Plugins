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
- External library import from Mill dependency metadata via `resolvedMvnDeps` / `resolvedIvyDeps`, with `compileClasspath` as fallback.
- Scala SDK import for Scala Mill modules using Mill metadata-backed Scala classpaths.
- `build.mill` editing support with Scala file recognition and Mill script resolve/completion roots.
- Per-module task nodes for actionable resolved targets such as `compile`, `test`, and `runBackground`.
- A `Reload Mill Project` action under `Build` for refreshing linked Mill imports on demand.
- Structured progress events for Mill project import.
- A Mill Project Settings page under `Build Tools`.
- Standard unlinked-project detection so Mill projects can surface the IDE import prompt after open.
- A Mill-specific external-system view contributor for cleaner task and dependency labels in the tool window.
- A dedicated Mill task tree in the external-system tool window while keeping direct task execution.
- A registered `Mill` tool window factory so linked Mill projects now show a real External System tool window, not just task data behind the scenes.
- A `Link Mill Project` action so existing IDEA projects can explicitly attach a Mill build and open the Mill tool window.

### Changed

- Changed the Mill tool window task tree to use the raw dotted task names as nested path segments instead of synthetic
  task groups or display-name rewriting.
- Refined Mill project settings so executable selection is split into project script and manual command/path modes,
  while still offering a PATH shortcut suggestion, with project-local wrapper detection preferred by default and the
  unused JVM options field removed.

### Deprecated

### Removed

### Fixed

- Fixed Mill local settings state initialization so project save no longer fails on the local settings component.
- Added a Mill project-open processor so Mill project directories participate in the IDE project-open flow instead of
  only manual external-system linking.
- Fixed Mill tool window task execution by registering a standard external-system task run configuration type and
  restoring runnable task nodes in the Mill tool window.

### Security
