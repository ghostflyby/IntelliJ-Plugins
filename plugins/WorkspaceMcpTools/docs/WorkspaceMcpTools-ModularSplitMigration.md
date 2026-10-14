# WorkspaceMcpTools Modular Split Migration (Plugin Model v2)

Status: Implemented
Completed: 2026-09-21
Supersedes: the classic single-descriptor layout described in `WorkspaceMcpTools-BackendOnlyRuntime.md`

## What Changed

The plugin is now a modular (Plugin Model v2) plugin with three content modules loaded per process side:

| Module                                  | Gradle project | Loads in                      | Content                                                               |
|-----------------------------------------|----------------|-------------------------------|-----------------------------------------------------------------------|
| `dev.ghostflyby.mcp.workspace.shared`   | `shared`       | monolith + backend + frontend | `message()` bundle, `pluginVersion`, `runsOnIdeBackendSide()`         |
| `dev.ghostflyby.mcp.workspace.frontend` | `frontend`     | monolith + frontend           | skill notification activity, notification group, notify-once settings |
| `dev.ghostflyby.mcp.workspace.backend`  | `backend`      | monolith + backend            | REST server, all routes, project resolver, startup activity           |

- Root `plugin.xml` declares only metadata plus the `<content>` modules (the official modular
  template declares no product-module dependencies at the root); the frontend/shared module
  descriptors live at `<subproject>/src/main/resources/<module-id>.xml` (registered via
  `projects.txt`, convention `repo.intellij-module`, packaged with IJPG `pluginModule`).
- The **backend** is an `loading="embedded"` content module whose classes are composed into the
  main plugin jar (`pluginComposedModule`) and whose descriptor lives in the root resources, so it
  runs on the MAIN plugin classloader — the documented "plugin with dependencies" model where all
  `lib/` jars are on the classpath ("All JARs from the /lib folder are automatically added to the
  classpath"; "Do Not Repackage Libraries"). A separate content-module classloader sees only its
  own `lib/modules/<module-id>.jar` (+ platform core), which is why backend cannot stay a separate
  jar: ktor/snakeyaml live in `lib/`, and the platform's `intellij.libraries.ktor.*` core library
  modules (io.ktor 3.4.1: client/client.cio/io/network.tls/server.cio/utils) provide server
  cio/core but **no content-negotiation** (that arrives in 263/master; IJPL-237683 tracks multi-jar
  support and currently prescribes repackaging into the module jar). Its descriptor still requires
  `intellij.platform.backend` (+`vcs.impl` for `GenericPatchApplier`), so the module — and the REST
  server activity — do not load on split-mode frontends. The **frontend** module (notification)
  requires `intellij.platform.frontend` and needs no third-party libraries, so it stays a separate
  content module and renders in the JetBrains Client, where Copy/Reveal act on the local machine's
  own plugin sandbox copy of `agent-skills/`. Monolith behavior is unchanged; in split mode the
  REST server runs only in the backend.
- `splitMode = true` + `pluginInstallationTarget = BOTH` in the root build script;
  `.run/runIdeBackend.run.xml`, `runIdeFrontend.run.xml`, and `runIdeSplitMode.run.xml` are
  generated for local two-process debugging.
- Version 2.1.0.

## Platform Facts Discovered (source-verified against 261)

1. `PluginDescriptorLoader.loadPluginSubDescriptors` resolves each content module jar as
   `lib/modules/<module-id>.jar`; if that jar is absent it falls back to the plugin's `lib/` jars
   and throws `Cannot resolve <module-id>.xml`. IJPG names module jars
   `<rootProjectName>.<project-path>.jar` (the official template matches its module ids to this by
   having `rootProject.name == "modular.plugin"`), so each subproject overrides the `composedJar`
   archive name with its module id.
2. Content-module classloaders get the whole plugin `lib/` classpath (ktor/snakeyaml land there
   and are visible), while classes of *sibling modules* are reachable only through the
   `<dependencies><module/></dependencies>` declarations, which double as the loading gate.
3. `GenericPatchApplier` and the rest of `com.intellij.openapi.diff.impl` live in
   `intellij.platform.vcs.impl` — the pre-migration `com.intellij.modules.vcs` dependency was
   load-bearing for PATCH routes, not dead as suspected in the backend-only note.
4. Cross-module helpers moved to the shared module had to become `public` (ABI-tracked under
   `explicitApi()`/`abiValidation`): `message()`, `pluginVersion`, `runsOnIdeBackendSide()`; the
   frontend module additionally exposes `bundledSkillPath()`.
5. `PluginPathManager.getPluginResource` resolves by class-file location and rejects `lib/modules`
   layouts; `bundledSkillPath()` resolves through the plugin descriptor (`PluginManagerCore.getPlugin(id).pluginPath`)
   instead.

## Verification Status

- All tests green after the split (root 2, frontend 6, backend 165); `@TestApplication`,
  platform fixtures, and the ktor test host work inside `repo.intellij-module` subprojects.
- Distribution zip layout verified: `lib/modules/{shared,frontend,backend}.jar` plus `lib/`
  third-party jars and the bundled `agent-skills/` directory.
- Interactive split-mode verification (backend binds 63441+, notification on the frontend,
  descriptor loading with the renamed module jars) still needs one `runIdeSplitMode` pass — run
  the generated `.run/runIdeSplitMode.run.xml`, or `runIdeBackend` + `runIdeFrontend` Gradle tasks
  in two terminals. The only runtime attempt so far was aborted before the jar-naming fix, so
  treat this pass as pending.

## Remote-Development Reachability (unchanged limitation)

Port forwarding exists as a user action (Backend Control Center → Ports, or the 2026.2 terminal
Port Forwarding widget; persisted in `forwardedPorts.xml`) but there is no public API to register
a forwarded port programmatically. JetBrains' own MCP server has the same constraint
(IJPL-200507). Until an RPC bridge exists: run the agent on the backend host or forward port
63441 manually.
