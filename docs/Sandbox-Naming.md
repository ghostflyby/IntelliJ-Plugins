# Sandbox And Module-Jar Naming (IJPG 2.x)

Status: Implemented
Completed: 2026-11-24

## Problem

The IntelliJ Platform Gradle Plugin keys every project's IDE sandbox by the Gradle leaf
`project.name` only:

```
<intellijPlatform.sandboxContainer>/<project.name>/<productCode>-<version>/
```

and the `intellij-platform.module` plugin registers `prepareSandbox` for content-module
subprojects as well. In this monorepo, subproject names are directory leaf names
(`settings.gradle.kts` registers `plugins/<PluginDir>/<leaf>` via `projects.txt`), so two
plugins that both define a `frontend` (or `shared`/`backend`) subproject resolve to the
*identical* sandbox directory — `.intellijPlatform/sandbox/frontend/IU-2026.1/…` — sharing
config/system/logs/plugins-test state, cross-installing pseudo-plugins, and producing
nondeterministic test output. This was observed on disk once WorkspaceMcpTools introduced
its `shared`/`frontend`/`backend` subprojects (e.g. `plugins-test/frontend/lib/…`).

Module jars have a different quirk: IJPG names them
`<rootProjectName>.<dotted project path>.jar` (unique, but not resolvable by the platform),
while `PluginDescriptorLoader.loadPluginSubDescriptors` only accepts
`lib/modules/<module-id>.jar` — so every content module had to hand-override
`composedJar.archiveFileName` with its module id.

## Fix (buildSrc/src/main/kotlin/repo.intellij-module.gradle.kts)

For every non-root project applying `repo.intellij-module`:

1. `intellijPlatform.sandboxContainer` is redirected to
   `.intellijPlatform/sandbox/<dotted project path>` (e.g.
   `.intellijPlatform/sandbox/plugins.WorkspaceMcpTools.frontend/frontend/IU-2026.1/…`),
   making the sandbox location repo-wide unique.
2. `intellijPlatform.projectName` is set to the same dotted path, so the in-sandbox plugin
   directory and build-archive naming follow suit.
3. When the subproject ships exactly one module descriptor XML at its
   `src/main/resources/` root, `composedJar.archiveFileName` is set to
   `<module-id>.jar` automatically — the platform-required rename can no longer be forgotten.

Plugin roots keep their previous naming (leaf names are unique among plugin directories), so
existing root sandboxes do not move: a plugin project also applies `repo.intellij-module`, and
the convention therefore restores `sandboxContainer`/`projectName` whenever
`repo.intellij-plugin` is applied in the same project, overriding the dotted-path values it set
for content-module subprojects. Stale leaf-named sandbox directories (`shared`, `frontend`,
`backend`, `intellij-shared`) were deleted once; they are disposable build caches.

## Known Leftover

IJPG's `generateSplitModeRunConfigurations` writes `runIdeBackend.run.xml`,
`runIdeFrontend.run.xml`, and `runIdeSplitMode.run.xml` into each plugin's own `.run/`
directory (no file conflicts), but the run-configuration *display names* are fixed upstream
constants (`Run IDE (Backend)` etc.) and the compound configuration references its children
by name. With a second split-mode plugin in one IDEA workspace these names duplicate. Fixing
this needs a post-processing/own-generation pass over the `.run` XML; tracked as a follow-up
and intentionally not addressed here.
