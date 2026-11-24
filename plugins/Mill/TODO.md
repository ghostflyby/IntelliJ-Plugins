# Mill TODO

Status: In Progress
Last Updated: 2026-11-24

## Minimal

1. Keep project open, unlinked-project detection, and basic import stable.
2. Keep a single-module fallback import path working even when detailed Mill metadata resolution fails.
3. Keep basic task execution and settings persistence reliable.

## Recently Completed

1. Migrated to the modular Plugin Model v2 layout (`shared` + embedded `backend` content
   modules) with split-mode development (`runIdeBackend` / `runIdeSplitMode`); see
   `docs/Mill-ModularSplitMigration.md`.
2. WSL execution targets: linked projects under `\\wsl.localhost\...` import and run Mill
   inside the distribution (`wsl.exe -d ... --exec`), with target↔UNC path conversion for
   imported roots, libraries, and JDK homes; EEL container targets are detected and reported
   as unsupported; see `docs/Mill-WslEelExecution.md`.
3. Converted the remaining Java sources (`MillExternalSystemManager`, `MillSettings`) to
   Kotlin with settings-file-compatible state, and demoted success-path `warn` logging to
   `info`.
4. Replaced the configurable's test polling with completion-notification tests (suspend methods,
   `Deferred` listeners, class-level `@Timeout`) and fixed the two adapter defects they exposed
   (a listener losing the change that landed just after registration, and per-listener
   collectors leaving delivery order undefined); see `docs/Mill-ViewModelNotifications.md`.
5. Kept WSL execution working where the platform's EEL daemon cannot start: `wsl.exe` is
   spawned through a plain `ProcessBuilder`, no UNC working directory is passed, and bare
   commands are resolved inside the distribution through the login shell; the WSL CI leg
   exercises the same spawn path.

Archived notes:
`plugins/Mill/docs/Mill-ToolWindowTasks.md`,
`plugins/Mill/docs/Mill-ModularSplitMigration.md`,
`plugins/Mill/docs/Mill-WslEelExecution.md`,
`plugins/Mill/docs/Mill-ViewModelNotifications.md`

## Active Next

1. One interactive `runIdeSplitMode` verification pass (open a Mill project, check import,
   tool window, settings and notifications in the JetBrains Client).
2. Add `externalSystemOutputParserProvider` for structured Mill build, test, and error output.
3. Add `externalSystemExecutionConsoleManager` and notification integration for better task execution UX.
4. Add config locator and watcher contributors so auto-reload follows Mill config changes more precisely.
5. Expand custom project data services beyond Scala SDK once more Mill-specific `DataNode` types are introduced.

## WSL/EEL Follow-ups

1. Forward `MillExecutionSettings.env` into WSL targets (WSLENV plumbing).
2. Remote Topic notification bridge so split-mode frontends render import balloons.
3. EEL container target execution once the Ijent-backed test fixtures publish
   (IJPL-178929 / IJPL-243361 — same blockers EnhancedHotSwapEnabler parked on).

## Gradle/Maven Level

1. Support dependency analyzer integration with navigable dependency graphs and conflict inspection.
2. Support run configuration import, before-run task import, and richer execution delegation.
3. Support recovery, sync diagnostics, and issue classification close to mature external-system plugins.
4. Reach stable project sync behavior for multi-module, test, generated-source, and mixed Scala/Java Mill builds.
