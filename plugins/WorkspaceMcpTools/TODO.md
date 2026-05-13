# WorkspaceMcpTools TODO

Status: In Progress
Last Updated: 2026-05-13

## Refactor Plan

### Core/Feature Boundary Refactor (In Progress)

1. ✅ Add `WorkspaceMcpFeature` interface for SDK-neutral feature registration boundary.
2. ✅ Add `WorkspaceMcpRequestRunner` for centralized project resolution, context installation, and error mapping.
3. ✅ Extract core metadata resources (server/info, projects, projects/{key}) into `dev.ghostflyby.mcp.core`.
4. ✅ Extract VFS resources into `dev.ghostflyby.mcp.vfs.resources`.
5. ✅ Extract document resources into `dev.ghostflyby.mcp.document.resources`.
6. ✅ Add serialization-first SDK tool scaffold (`dev.ghostflyby.mcp.sdk.tools`) with typed schema support and proof tools (`vfs_refresh`, `vfs_exists`).
7. ✅ Refactor `WorkspaceMcpSdkServerService` to delegate to features and request runner.
8. ⬜ Migrate remaining resource read logic from `WorkspaceResourceReader` to feature-owned readers (future).
9. ⬜ Migrate old annotation-based toolsets to SDK tools incrementally.
10. ⬜ Finalize old/new package cleanup once feature migration is complete.

### Earlier Plans

1. Add a docs-generation workflow to keep tool inventories synchronized between code and docs.
2. Isolate scope-provider compatibility/reflection behavior behind a dedicated compatibility layer.
3. Add contract regression tests for quick presets, canonical enum values, and descriptor compatibility.
4. Continue reducing duplicated validation/activity text by expanding shared common helpers.

## Done Criteria

1. Feature boundary provides clear interface for listable resources, templates, tools, and event hooks.
2. Request runner centralizes project resolution and context installation; all new tool/resource handlers use it.
3. Core, VFS, and document resources live in their final packages.
4. SDK proof tools (`vfs_refresh`, `vfs_exists`) demonstrate the new pattern.
5. Existing annotation-based toolsets remain functional.

## Post-Implementation Archive

Move this file content into:
`plugins/WorkspaceMcpTools/docs/WorkspaceMcpTools-CoreFeatureBoundary.md`
