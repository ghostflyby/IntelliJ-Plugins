# Toolset First-Call Optimization Notes

> 状态：已实现
> 最后核对：2026-02-26

## Scope Toolset
- Added `scope_get_default_descriptor` with preset defaults.
- Added `scope_resolve_standard_descriptor` for direct standard scope conversion.
- Added `scope_catalog_find_by_intent` for compact intent-based catalog retrieval.
- Added `scope_normalize_program_descriptor` for descriptor normalization and migration.

## Scope File Search
- Added `scope_find_source_file_by_class_name`.
- Default behavior falls back to `All Places` when scope is not provided.
- Optional `preferSources=true` ranks source-like paths above binaries/artifacts.

## Scope Symbol Search
- Added `scope_search_symbols_quick` for low-parameter first calls.
- Added `scope_search_symbols_with_stage_progress` with stage counters.
- Added `scope_search_symbols_healthcheck` for index/provider readiness check.

## Navigation
- Added `navigation_get_symbol_info_by_offset`.
- Added `navigation_get_symbol_info_auto_position` (row/column or offset).
- Added `navigation_get_symbol_info_quick` returning normalized position fields.

## VFS
- Added `vfs_read_api_signature` to return structured imports/type/member snapshot.

## Suggested Regression Cases
- Verify default preset path: `scope_get_default_descriptor(PROJECT_FILES)` returns valid descriptor.
- Verify catalog intent truncation behavior with small `maxResults`.
- Verify symbol healthcheck returns fallback mode when in-scope provider is unavailable.
- Verify navigation auto-position rejects invalid combinations (both offset and row/column).
- Verify API signature extraction handles files without imports or without type declarations.

## P2 Additions
- Added shared scope shortcut support (`ScopeQuickPreset` + shared standard descriptor builder) to avoid duplicated scope-assembly logic.
- Added `scope_search_files_quick` for preset-based first-call file search.
- Added `scope_search_text_quick` for preset-based first-call text search (plain/regex).
- Added `quality_get_scope_problems_quick` for preset-based scope diagnostics.
- Added `quality_get_scope_problems_by_severity_quick` for preset-based severity aggregation.
- Added `quality_fix_scope_quick_by_preset` for preset-based scope quick-fix pipeline.
