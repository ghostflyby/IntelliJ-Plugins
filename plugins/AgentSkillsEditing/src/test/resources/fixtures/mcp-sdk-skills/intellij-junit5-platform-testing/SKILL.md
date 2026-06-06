---
name: intellij-junit5-platform-testing
description: Use when writing, migrating, or reviewing IntelliJ Platform tests that use the JUnit 5 test framework fixtures, including project/module/source-root setup, fixture lifecycle, annotations, and common safe patterns.
---

# IntelliJ JUnit 5 Platform Tests

Use this skill when working on IntelliJ Platform tests based on `com.intellij.testFramework.junit5.fixture`.
Keep it focused on general test-fixture mechanics, not plugin-specific behaviour.

## First Checks

- Use JUnit 5 imports: `org.junit.jupiter.api.Test`, `org.junit.jupiter.api.Assertions.*`, `BeforeEach`, `AfterEach`,
  etc.
- Add `@TestApplication` to tests that need IntelliJ application services, project fixtures, VFS/PSI/file types,
  indexing, or platform components.
- Prefer official fixture APIs from `com.intellij.testFramework.junit5.fixture` over handwritten setup utilities.
- If an API shape is uncertain, inspect the active IntelliJ Platform `fixtures.kt` source for the exact version in use.

## Fixture Lifecycle

- A fixture stored in an instance field is initialized per test method under normal JUnit 5 test-instance lifecycle.
- A fixture stored in a static/companion object is shared intentionally. Do this only when sharing is safe and isolation
  is not required.
- Prefer instance-level fixture fields for normal tests:

```kotlin
@TestApplication
internal class ExampleTest {
    private val projectFixture = projectFixture(openAfterCreation = true)
    private val project by projectFixture
}
```

- Do not add per-test factory code just to get method isolation. Instance fixture fields already provide it.
- Be careful with global mutable test hooks. Prefer per-test injected strategy objects or fixture-local state.

## Project Fixtures

- `projectFixture(openAfterCreation = true)` is the usual starting point when the test needs an opened project.
- Use an explicit `pathFixture = tempPathFixture()` if other fixtures need to derive paths from the same project root.
- Avoid accessing `project.stateStore.projectBasePath` during fixture construction unless the project fixture has
  already initialized and the API you use requires it. Prefer fixture APIs such as `pathInProjectFixture`.
- Project-relative product code usually resolves paths from `project.basePath`; if the test targets project-relative
  behaviour, keep the relevant files under the project path.

## Module Fixtures

- Prefer the non-path module fixture when the module does not need a module file on disk:

```kotlin
private val moduleFixture = projectFixture.moduleFixture(name = "test-module")
```

- Use the path-based overload only when a real path-backed module/content root is part of the test contract:

```kotlin
private val projectPathFixture = tempPathFixture()
private val projectFixture = projectFixture(pathFixture = projectPathFixture, openAfterCreation = true)
private val moduleFixture = projectFixture.moduleFixture(projectPathFixture, addPathToSourceRoot = true)
```

- Do not use the path-based overload merely to get a module. It may introduce unnecessary filesystem coupling.

## Source Roots And Files

- Prefer `moduleFixture.sourceRootFixture(...)` for source roots.
- For project-relative tests, create the source root under the project path with
  `projectFixture.pathInProjectFixture(Path("someRoot"))`.
- For tests that only need module/index/source-root semantics and not project-relative paths, a temporary source-root
  path can be enough.
- Use `blueprintResourcePath` for a whole static fixture tree. This is often clearer than creating many individual
  files.
- If using a resource path, convert resource URLs with URI-aware APIs:

```kotlin
private val blueprint = Path.of(
    requireNotNull(javaClass.getResource("/fixtureTree")).toURI(),
)
```

- Avoid `resource.path` when the path may contain spaces or URL-escaped characters.
- `virtualFileFixture(name, content)` creates a direct child file of a `PsiDirectory` fixture. Treat `name` as a child
  name, not a nested relative path.
- If `sourceRootFixture` copies files with `blueprintResourcePath`, refresh the created VFS directory when the copied
  content must be visible immediately:

```kotlin
@BeforeEach
fun refreshFixtureContent() {
    sourceRootFixture.get().virtualFile.refresh(false, true)
}
```

## Indexing

- Wait for indexes before asserting behaviour that depends on IntelliJ indexes:

```kotlin
IndexingTestUtil.waitUntilIndexesAreReady(project)
```

- Files normally need to be under a module/source root for many index-backed behaviors to be meaningful.
- Prefer file types that exist in the test environment. Do not assume optional language plugins are installed.

## Assertions And Verification

- Migration from JUnit 4 to JUnit 5 changes assertion argument order for messages:

```kotlin
assertTrue(condition, "message")
assertEquals(expected, actual)
```

- When a test name says a fast path or special route is used, make that route observable. Result equality alone often
  only proves behaviour, not the path taken.
- Avoid top-level mutable state for route-observation tests. Inject a small strategy or fake object per test instead.
- If filetype needed, use common types like plain text or XML instead of plugin-dependent ones like Kotlin unless the
  plugin is explicitly depending on the providing plugin.

## Cleanup

- Prefer fixture-owned clean-up to manual deletion.
- Close external clients, transports, and coroutine scopes in `@AfterEach`.
- Do not use shell-based file creation in tests when fixture APIs can express the setup.
