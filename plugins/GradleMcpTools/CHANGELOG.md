<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Gradle MCP Tools Changelog

## [Unreleased]

### Added

- Gradle MCP tools for listing linked Gradle projects and tasks, running Gradle tasks, syncing linked projects, and
  canceling active Gradle executions.
- Simplified Chinese localization for Gradle MCP activity messages.

### Changed

- Gradle task and project operations now report MCP activity updates for better runtime observability.
- `run_gradle_tasks` now uses IDE background progress so task runs are visible and cancelable from the IDE.
