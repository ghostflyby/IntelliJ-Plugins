# IntelliJ Plugins

## Coding

1. use kotlin for plugin development
2. use kotlin coroutines whenever possible for asynchronous operations, including Psi/Vfs
   read/write actions, progress reporting,
   background tasks, and some cancelable registrations. Avoid blocking the UI thread.
3. you should declare `CoroutineScope` in Service as primary constructor property when needed,
   ensuring proper lifecycle, e.g. cancellation on plugin unload.
4. make dynamic plugins, registering should pass a `Disposable`
   or `CoroutineScope` to ensure proper cleanup on unload.
5. clean data in `UserDataHolder` with `Disposable` or `CoroutineScope` to prevent memory leaks.
6. DO NOT run blocking operations on UI Thread.
7. use explicit visibility modifiers, mostly should use `internal`
8. DO NOT use `@ApiStatus.Internal` APIs, which will be prevented on marketplace, check before writing
9. Use `@ApiStatus.Experimental` APIs with caution, as they may change without deprecation.
   If you must use them, `Suppress` the `UnstableApiUsage` warning and document the usage clearly in code comments,
   so future maintainers understand the risks and can track API changes in IntelliJ releases.

## Project Structure

1. this repository is a Gradle monorepo; `settings.gradle.kts` auto-includes each
   directory under `plugins/` as `:plugins:<pluginName>`.
2. current plugin directories:
   `EnhancedHotSwapEnabler`, `GradleMcpTools`, `IdeaVimToggleIME`,
   `LiveTemplatesWithSelection`, `macOSRecents`, `SpotlessIntegration`,
   `VitePress`, `WorkspaceMcpTools`.
3. most plugin modules should keep this layout:
   `build.gradle.kts`, `src/`, `README.md`, `CHANGELOG.md`, `TODO.md`, and `api/*.api`
   when the plugin exposes a stable external API.
4. some plugins can have nested Gradle subprojects via `projects.txt`
   (current example: `plugins/SpotlessIntegration/projects.txt` includes `ModelBuilderService`).
   when adding/removing entries, keep directory names and Gradle includes in sync.
5. `VitePress` currently does not fully follow the common documentation/layout convention.
   if you touch it, align it with the common plugin layout where practical.
6. treat generated/local artifacts as non-source: `**/build/`, `**/.gradle/`, `.idea/`,
   `.intellijPlatform/`, and `**/.DS_Store`.
7. every plugin should keep a root `TODO.md` for current planned/refactor work.
   keep it short, actionable, and written in English.
8. once a `TODO.md` plan is implemented, move its finalized notes into plugin-local `docs/`
   using a descriptive filename (for example `<PluginName>-<Topic>.md`), then refresh `TODO.md`
   with the next active plan items.

## Tooling

1. MUST use MCP tools if available for the task, as they provide better performance,
   reliability, and agent-friendly semantics than direct PSI/VFS access.

## PSI/VFS Read-Write Safety

1. all PSI/VFS/document reads must run inside `readAction`/`runReadAction`.
2. do not assume caller context for internal helpers that read PSI/VFS.
   add explicit guards where useful (for example read-access assertions in deep helper functions).
3. after mutating editor `Document` in write action, call
   `PsiDocumentManager.doPostponedOperationsAndUnblockDocument(document)` and
   `PsiDocumentManager.commitDocument(document)` before returning.
4. for offset-based navigation/symbol tools, prefer committed documents from
   `PsiDocumentManager.getLastCommittedDocument(psiFile)` to keep PSI/document consistent.
5. if committed document is unavailable, fail with a clear retriable message
   (ask caller to commit/retry) instead of silently using potentially stale/uncommitted state.

## MCP Tool Contract

1. prefer MCP-first design for library/code insight lookup.
   do not parse IDE jars via shell tools unless MCP lookup path is exhausted and failure reasons are recorded.
2. provide first-call shortcuts for agent usage with stable, non-interactive defaults.
3. batch tools should provide `continueOnError` and per-item error payloads.
4. use `reportActivity` for start/progress/finish on long-running tools.
5. validate parameters early and use precise `mcpFail` messages with actionable guidance.
6. extract repeated descriptions/logic to shared `common` helpers and constants.

## Serialization Compatibility

1. treat tool DTOs/enums as external contracts once released.
2. when renaming enum values, keep backward-compatible aliases using `@JsonNames`.
3. prefer additive changes over breaking removals; if removal is required, document migration.
4. keep quick presets and scope/token contracts backward-compatible across versions.

## Build

1. use Gradle for build
2. you don't need to run `verifyPlugin`, which can be done on CI
3. a single Gradle sync takes about 1.5-2 minutes
4. a single `buildPlugin` Gradle task may take about 2 minutes
5. update the kotlin ABI file when changing public APIs
6. after MCP tool changes, run project compile and plugin packaging (`buildPlugin`) before commit.

## Diagnostics and Docs

1. keep activity keys/messages in sync with tool lifecycle and remove stale keys when tool interfaces are removed.
2. when adding/changing toolsets, update `README.md` and related design docs in `docs/`.
3. maintain clear separation between implemented and planned items in docs to reduce agent confusion.
4. record newly discovered first-call shortcuts and failure/retry patterns in docs incrementally.
5. treat plugin `TODO.md` as a staging document only; completed work must be archived in `docs/`
   with a topic-specific filename to keep planning and historical records separated.

## Common Pattern

### plugin level disposable

```kotlin
@Service
private class MyDisposable : Disposable.Default

@Suppress("LocalVariableName")
internal val PluginDisposable
get() = service<PluginDisposable>()

```
