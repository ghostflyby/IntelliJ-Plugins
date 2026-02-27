# IntelliJ Plugins

## Coding

1. use kotlin for plugin development
2. use kotlin coroutines whenever possible for asynchronous operations, including Psi/Vfs
   read/write actions, progress reporting,
   background tasks, and some cancelable registrations. Avoid blocking the UI thread.
3. you should declare `CoroutineScope` in Service as primary constructor property when needed,
   ensuring proper lifecycle, .e.g cancellation on plugin unload.
4. make dynamic plugins, registering should pass a `Disposable`
   or `CoroutineScope` to ensure proper cleanup on unload.
5. clean data in `UserDataHolder` in with `Disposable` or `CoroutineScope` to prevent memory leaks.
6. DO NOT run blocking operations on UI Thread.
7. use explicit visibility modifiers, mostly should use `internal`
8. DO NOT use `@ApiStatus.Internal` APIs, which will be prevented on marketplace, check before writing
9. Use `@ApiStatus.Experimental` APIs with caution, as they may change without deprecation.
   If you must use them, `Supress` the `UnstableApiUsage` warning and document the usage clearly in code comments,
   so future maintainers understand the risks and can track API changes in IntelliJ releases.

## Project Structure

under `plugins`, each plugin in its own subdirectory, with a `CHANGELOG.md`
and `README.md` for documentation and changelog.

## Tooling

1. use MCP tools if available for the task, as they provide better performance,
   reliability, and agent-friendly semantics than direct PSI/VFS access.

## Build

1. use Gradle for build
2. you don't need to run `verifyPlugin`, which can be done on CI
3. a single Gradle sync takes about 1.5-2 minutes
4. update the kotlin ABI file when changing public APIs

## Common Pattern

### plugin level disposable

```kotlin
@Service
private class MyDisposable : Disposable.Default

@Suppress("LocalVariableName")
internal val PluginDisposable
get() = service<PluginDisposable>()

```