# Mill

<!-- Plugin description -->
Integrates the Mill build system into IntelliJ IDEA through the External
System API.

Current implementation provides:

- Mill project root detection from `build.sc`, `mill.sc`, `build.mill`, and `.mill-version`
- Project Settings controls for executable path, Mill JVM options, metadata-backed import, and per-module task nodes
- Best-effort multi-module import from Mill target resolution
- Mill metadata-backed source, resource, and generated-root import where `show <module>.*` is available
- Explicit test-to-production module dependencies for discovered `*.test` modules
- Direct Mill module dependency import from `show <module>.moduleDeps` where discovered modules can be matched
- External library import from Mill dependency metadata via `resolvedMvnDeps` / `resolvedIvyDeps`, with `compileClasspath` as fallback
- Scala SDK import for Scala Mill modules from `scalaVersion`, `scalaCompilerClasspath`, `scalaDocClasspath`, and `ammoniteReplClasspath`
- Per-module task nodes for actionable resolved targets such as `compile`, `test`, and `runBackground`
- A `Reload Mill Project` action under `Tools` for refreshing linked Mill builds on demand
- Structured import progress reporting during Mill project resolution
- Basic Mill task entries and direct CLI-backed task execution
- Project-open integration so Mill projects can be opened/imported without falling straight into BSP
- Standard unlinked-project detection so opened Mill projects can show the IDE import prompt and link flow
- A Mill-specific external-system view contributor for cleaner task and dependency names in the tool window
- A Project Settings entry under `Build Tools` with basic linked-project configuration
- Scala plugin dependency wiring with Scala SDK configuration during import

<!-- Plugin description end -->
