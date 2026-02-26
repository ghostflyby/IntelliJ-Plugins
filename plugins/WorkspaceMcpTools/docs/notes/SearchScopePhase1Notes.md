# SearchScope Phase 1 Notes

> 状态：部分实现（含待办项）
> 最后核对：2026-02-26

## Tool Usage Record
- `vfs` tools:
  - Used `vfs_list_files` + `vfs_read_file_by_line_range` to inspect IntelliJ source JAR APIs (`SearchScope`, `GlobalSearchScope`, `PackageSetFactory`, `NamedScope`, `NamedScopesHolder`, `PredefinedSearchScopeProvider`).
  - Used `vfs_read_file_by_line_range` to review existing plugin code patterns (`DocumentMcpTools`, `VfsMcpTools`, `SymbolNavigationMcpTools`).
  - Used `vfs_refresh` after creating new files, because new files were not immediately visible to VFS reads.
- `document` tools:
  - Used `document_get_text_length` on `plugin.xml` to verify editor-backed document state during integration.
- `navigation` tools:
  - Used `navigation_to_reference` to verify symbol navigation correctness (e.g. `VFS_URL_PARAM_DESCRIPTION` usage to declaration).
- web search:
  - Queried JetBrains docs for scope language syntax and scopes configuration practices.

## Observed Tooling Limitations / Bugs
- `vfs_read_file_by_line_range` requires exact in-bound line ranges; probing often needs an extra round-trip to discover total line count.
- Newly created files may return "file not found" until explicit `vfs_refresh`.
- `navigation` works best for project source files; for JAR virtual sources, direct VFS read is usually more reliable.
- Some IntelliJ APIs in source JAR are `@ApiStatus.Internal`; using them directly triggers stability checks in this plugin build.

## SearchScope Phase 1 Gaps and Future Work
- Provider scopes:
  - `SearchScopeProvider` APIs differ by platform build; implementation uses reflective invocation for compatibility.
  - Improved: catalog output now includes provider reflection diagnostics (missing compatible methods / invoke failures / invalid return shapes).
  - Future: introduce a dedicated adapter layer with per-build test matrix.
- Stable scope IDs:
  - To avoid `@ApiStatus.Internal` dependency on `ScopeIdMapper`, current mapping is based on known standard English IDs + localized display-name matching.
  - Future: add a dedicated compatibility mapping utility and regression tests across locales.
- Stateless operation:
  - Current design is fully stateless; each call resolves from request payload (`atoms + tokens`) without handle reuse.
  - Tool outputs now return `ScopeProgramDescriptorDto`, which can be fed directly into future search tools as scope input.
  - Future: if performance becomes an issue, consider optional client-side memoization (not plugin-side registry/state).
- Program diagnostics:
  - Improved: `strict=false` now supports configurable fallback semantics:
    - request-level `nonStrictDefaultFailureMode`
    - atom-level override `ScopeAtomDto.onResolveFailure`
    - supported modes: `SKIP` / `EMPTY_SCOPE` / `FAIL`.
  - Future: extend fallback control from atom granularity to kind-level policy templates.
- Catalog completeness:
  - Current catalog includes predefined scopes, reflective provider scopes, named scopes, and module variants.
  - Future: add optional UI-context data binding for richer "Current File / Selection / Usage View" resolution.
