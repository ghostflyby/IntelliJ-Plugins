# Mill

<!-- Plugin description -->
Integrates the Mill build system into IntelliJ IDEA through the External
System API.

Current implementation provides:

- Mill project root detection from `build.sc`, `mill.sc`, `build.mill`, and `.mill-version`
- Best-effort multi-module import from Mill target resolution
- Best-effort aggregate compile classpath import into `External Libraries`
- Structured import progress reporting during Mill project resolution
- Basic Mill task entries and direct CLI-backed task execution
- Project-open integration so Mill projects can be opened/imported without falling straight into BSP
- Standard unlinked-project detection so opened Mill projects can show the IDE import prompt and link flow
- A Mill-specific external-system view contributor for cleaner task and dependency names in the tool window
- A Project Settings entry under `Build Tools` with basic linked-project configuration
- Scala plugin dependency wiring for future Scala-aware integration work

<!-- Plugin description end -->
