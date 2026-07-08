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

- The fixture framework is enabled by `@TestApplication`, which also carries `@TestFixtures`. If `.get()` reports that
  the fixture framework is not initialized, check that the test class is Kotlin, annotated with `@TestApplication`, and
  uses JUnit 5 `@Test`.
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
- Fixtures compose through `TestFixture<T>` dependencies. Use `private val value by fixture` in tests, and inside custom
  fixtures call `otherFixture.init()` before returning `initialized(value) { cleanup }`.

## Fixture Catalog

The active `test-framework-junit5` fixture source provides these reusable fixtures:

- `testNameFixture(lowerCaseFirstLetter = true)`: current JUnit display/test name, with the leading `test` prefix
  stripped by the framework.
- `tempPathFixture(root = null, prefix = "IJ", subdirName = null)`: temporary NIO path with automatic cleanup.
-

`projectFixture(pathFixture = tempPathFixture(), openProjectTask = OpenProjectTask.build(), openAfterCreation = false)`:
creates or opens a project at the fixture path and closes it on teardown.

- `projectFixture.pathInProjectFixture(path)`: derives a path under `project.stateStore.projectBasePath`.
- `projectFixture.fileOrDirInProjectFixture(relativePath)`: resolves an existing project-relative path to `VirtualFile`.
- `projectFixture.moduleInProjectFixture(name)`: resolves an existing module by name.
- `projectFixture.moduleFixture(name = null, moduleType = null)`: creates a non-persistent module.
- `projectFixture.moduleFixture(pathFixture, addPathToSourceRoot = false, moduleTypeId = "")`: creates a path-backed
  module and can add that path as a source root.
- `disposableFixture()`: checked disposable owned by the test.
-

`moduleFixture.sourceRootFixture(isTestSource = false, pathFixture = tempPathFixture(), blueprintResourcePath = null)`:
creates a PSI directory source root and can copy a fixture tree from a resource path.

- `psiDirectoryFixture.psiFileFixture(name, content)`: creates a child `PsiFile` from text content.
- `psiDirectoryFixture.virtualFileFixture(name, content)`: creates a direct child `VirtualFile` from text content.
- `psiFileFixture.editorFixture()`: opens an editor for the PSI file, applies `<caret>` and selection markers from the
  document, commits the document, and closes the file during teardown.
- `projectFixture.fileEditorManagerFixture(initDockableContentFactory = false)`: replaces the project's
  `FileEditorManager` with a test `FileEditorManagerImpl`.
- `extensionPointFixture(epName) { extension }`: registers an extension and unregisters it on teardown.
- `registryKeyFixture(key) { setValue(...) }`: temporarily changes a registry key.
- `Application.replacedServiceFixture(serviceInterface) { service }` and `projectFixture.replacedServiceFixture(...)`:
  replace application or project services for the fixture lifetime.

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
- Use `editorFixture()` when the test needs an editor or caret. Put `<caret>` in file content instead of moving the
  caret manually where possible.
- There is no direct `BasePlatformTestCase.myFixture` replacement object. Compose project/module/source-root/file/editor
  fixtures and add small local helpers for copying fixture trees or resolving PSI directories when the built-in fixtures
  do not exactly match the old fixture operation.
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

## Migrating From BasePlatformTestCase

- Convert the class from `: BasePlatformTestCase()` to a plain JUnit 5 class with `@TestApplication`.
- Convert JUnit3-style methods such as `fun testSomething()` or backticked ``fun `test something`()`` to
  `@Test fun something()`.
- Replace `project` with `private val project by projectFixture`.
- Replace module/source-root setup with `projectFixture.moduleFixture(...)` and `moduleFixture.sourceRootFixture(...)`.
- Replace `myFixture.configureByText(...)` with a `sourceRootFixture.psiFileFixture(name, content).editorFixture()`
  chain.
- Replace `myFixture.copyDirectoryToProject(...)` with `sourceRootFixture(blueprintResourcePath = ...)` when the whole
  source root can be copied. If only one subdirectory from a shared resource tree is needed, create a small local helper
  fixture that copies that subtree into the project path, refreshes VFS, and resolves the target `PsiDirectory`/
  `PsiFile`.
- Replace `myFixture.configureFromTempProjectFile(...)` with project-relative path resolution plus
  `PsiManager.findFile`,
  then open an editor with `editorFixture()` when caret/editor APIs are needed.
- Replace old `assertSize`, `assertInstanceOf`, `assertSame`, `assertTrue(message, condition)` helpers with JUnit 5
  assertions such as `assertEquals(expected, actual)`, `assertInstanceOf(type, value)`, `assertSame(expected, actual)`,
  and `assertTrue(condition, message)`.

For tests like `AgentSkillsEditing` rename/navigation coverage, prefer a path-backed project fixture, a non-persistent
module or source root, a copied skill fixture tree, and `editorFixture()` for the opened `SKILL.md`. This preserves the
old `myFixture` semantics while removing the Vintage/JUnit3 discovery dependency.

## Cleanup

- Prefer fixture-owned clean-up to manual deletion.
- Close external clients, transports, and coroutine scopes in `@AfterEach`.
- Do not use shell-based file creation in tests when fixture APIs can express the setup.
