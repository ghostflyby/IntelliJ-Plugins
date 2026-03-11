# Mill TODO

Status: In Progress
Last Updated: 2026-03-10

## Minimal

1. Keep project open, unlinked-project detection, and basic import stable.
2. Keep a single-module fallback import path working even when detailed Mill metadata resolution fails.
3. Keep basic task execution and settings persistence reliable.

## Complete

1. Import real Mill modules, content roots, and inter-module dependencies instead of a single root module.
2. Add a dedicated `externalSystemViewContributor` for a usable Mill tool window structure.
3. Keep Scala SDK and external library import stable across common Scala Mill layouts.

## Extended

1. Add `externalSystemOutputParserProvider` for structured Mill build, test, and error output.
2. Add `externalSystemExecutionConsoleManager` and notification integration for better task execution UX.
3. Add config locator and watcher contributors so auto-reload follows Mill config changes more precisely.
4. Expand custom project data services beyond Scala SDK once more Mill-specific `DataNode` types are introduced.

## Gradle/Maven Level

1. Support dependency analyzer integration with navigable dependency graphs and conflict inspection.
2. Support run configuration import, before-run task import, and richer execution delegation.
3. Support recovery, sync diagnostics, and issue classification close to mature external-system plugins.
4. Reach stable project sync behavior for multi-module, test, generated-source, and mixed Scala/Java Mill builds.

## Post-Implementation Archive

Move this file content into:
`plugins/Mill/docs/Mill-InitialIntegrationPlan.md`
