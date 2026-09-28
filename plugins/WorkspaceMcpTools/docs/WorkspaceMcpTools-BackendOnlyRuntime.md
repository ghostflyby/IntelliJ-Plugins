# WorkspaceMcpTools Backend-Only Runtime

Status: Superseded by `WorkspaceMcpTools-ModularSplitMigration.md` (the plugin is now a modular
Plugin Model v2 plugin; this note remains for the pre-migration rationale)
Completed: 2026-09-21

## Goal

The plugin hosts PSI/VFS/index-backed services for local coding agents. Those capabilities live in the IDE backend
process, so the plugin now declares the backend as its only runtime location and never loads (or binds ports) in a
split-mode frontend (JetBrains Client).

## Implemented

1. `plugin.xml` requires `intellij.platform.backend`. Per the platform module contract
   (`platform/backend/module-info.md` in intellij-community), a plugin depending on this module loads in a monolith IDE
   and in a remote-development backend, and is skipped by a frontend process.
2. Startup activities additionally gate on `IdeProductMode.isFrontend` (2026.1: only `ProductMode.FRONTEND`), so the
   REST server and the skill notification stay backend-side even if the descriptor gate ever changes. The helper is
   `runsOnIdeBackendSide()` in `PluginInfo.kt`.
3. Regression test `PluginDescriptorTest` pins the descriptor dependency.

The plugin stays a classic single-descriptor plugin on purpose: the modular plugin model (content modules) is
Experimental and buys nothing here because there is no frontend half to ship.

## Feature Survey: What Migrates And What Cannot

Backend-native, no migration needed (per the official frontend/backend/shared API classification):

- file reads/metadata, glob, text search, fuzzy file search, symbol search, navigation, usages, documentation;
- inspections/problems, reformat/cleanup/optimize imports;
- file writes, patches, refactoring-aware move/delete;
- session state and `WorkspaceMcpSdkServerSettings` (backend-local persistent state).

Cannot fully migrate to a backend-only plugin (needs frontend code or is unreachable by design):

1. Skill onboarding notification. `NotificationGroup` is a frontend API, and the docs do not guarantee that balloons
   fired by backend code render in the JetBrains Client of a split-mode setup; the documented path is a shared Remote
   Topic plus a frontend listener. The actions also act on backend-host paths: `Copy Skill Path` stays meaningful (an
   agent running on the backend host uses the path), while `Reveal Skill Folder` and `Open Skill Online` only behave
   correctly in a monolith. Current behavior: exact in monolith, best-effort in remote development.
2. Bundled skill directory. `agent-skills/workspace-agent-bridge` ships inside the backend-side plugin sandbox; a
   frontend on another machine cannot see it, and plugin sync between sides covers Marketplace plugins only.
3. Loopback REST endpoint. The server binds `127.0.0.1:63441+` in the process that hosts it. In remote development the
   backend usually runs on another host, so a local agent cannot reach it without an SSH tunnel or port forward.
   Moving the listener to the frontend is impossible: the project model it serves lives on the backend.
4. Port scan range `63441..63540`. Before the backend-only gate, a local split-mode run (frontend and backend on one
   machine) let both processes bind separate ports; now only the backend binds.

Correction (2026-09-21): the `com.intellij.modules.vcs` dependency was NOT dead — `GenericPatchApplier`
(`com.intellij.openapi.diff.impl`) used by the PATCH routes lives in `intellij.platform.vcs.impl`. It is now declared
by the backend content module instead; see `WorkspaceMcpTools-ModularSplitMigration.md`.

## Follow-Ups

- Evaluate a Remote Topic notification bridge if split-mode UX for the skill notification matters.
- Add documented tunnel guidance for remote-development deployments if requested.
