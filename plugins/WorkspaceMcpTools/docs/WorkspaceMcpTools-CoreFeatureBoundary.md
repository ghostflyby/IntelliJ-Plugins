# WorkspaceMcpTools Core/Feature Boundary Refactor

Status: Archived
Completed: 2026-05-13

## Summary

WorkspaceMcpTools now has a clearer boundary between plugin core services and feature-owned resources/tools. The refactor introduced an SDK-neutral feature registration contract, centralized request execution, and moved core resource groups into their final package areas.

## Completed Work

1. Added `WorkspaceMcpFeature` as the SDK-neutral feature registration boundary.
2. Added `WorkspaceMcpRequestRunner` for centralized project resolution, context installation, and error mapping.
3. Extracted core metadata resources (`server/info`, `projects`, `projects/{key}`) into `dev.ghostflyby.mcp.core`.
4. Extracted VFS resources into `dev.ghostflyby.mcp.vfs.resources`.
5. Extracted document resources into `dev.ghostflyby.mcp.document.resources`.
6. Added serialization-first SDK tool scaffolding under `dev.ghostflyby.mcp.sdk.tools`, including typed schema support and proof tools (`vfs_refresh`, `vfs_exists`).
7. Refactored `WorkspaceMcpSdkServerService` to delegate to features and the request runner.

## Result

1. Feature registration has a clear interface for listable resources, templates, tools, and event hooks.
2. New tool and resource handlers can share request execution behavior through `WorkspaceMcpRequestRunner`.
3. Core, VFS, and document resources live in their final packages.
4. SDK proof tools demonstrate the new serialization-first pattern.
5. Existing annotation-based toolsets remained functional during the transition.

## Current Direction

This Resources-era refactor is archived. Current agent-facing workspace file access work is tracked through the REST API
contract in `plugins/WorkspaceMcpTools/TODO.md`.
