<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Workspace MCP Tools Changelog

## [Unreleased]

### Added

### Changed

### Deprecated

### Removed

### Fixed

### Security

## [1.0.0] - 2026-02-27

### Added

- Initial plugin scaffold with MCP Server dependency and VFS tool placeholders.
- `vfs_read_file` now supports `FULL`, `CHAR_RANGE`, and `LINE_RANGE` read strategies.
- Added convenience tools: `vfs_read_file_full`, `vfs_read_file_by_char_range`, `vfs_read_file_by_line_range`.
- Added `vfs_exists` to check whether a VFS URL currently resolves.
- Merged Document MCP tools into the same plugin and registered as a second MCP toolset.
- Refactored Document MCP APIs to map directly to `Document` methods (get/set/insert/delete/replace and line/offset
  queries), all as `suspend`.
- Added `scope_list` toolset entry to list project/module/directory/user-defined/predefined/Git-tracked scopes.
- Added `navigation_to_reference` tool to resolve reference target file and 1-based row/column.
- Renamed navigation toolset implementation class to `SymbolNavigationMcpTools`.
- Added navigation tools: `navigation_to_type_definition`, `navigation_to_implementation`,
  `navigation_find_overrides`, `navigation_find_inheritors`, `navigation_find_references`, and
  `navigation_get_callers`.
- Added SearchScope file-membership tools: `scope_contains_file` and `scope_filter_files`.
- Added scoped file search toolset `ScopeFileSearchMcpTools` with:
  `scope_search_files`, `scope_find_files_by_name_keyword`, `scope_find_files_by_path_keyword`,
  and `find_in_directory_using_glob`.
- Replaced generic MCP boundary list returns with serializable wrapper DTOs (`VfsFileNamesResult`,
  `NavigationResults`) for stronger boundary typing.
- Added batch VFS tools for higher-throughput agent calls:
  `vfs_get_url_from_local_paths`, `vfs_get_local_paths_from_urls`, `vfs_exists_many`,
  `vfs_file_stats`, `vfs_list_files_many`, and `vfs_read_files`.
- Added batch Document read tools: `document_get_texts` and `document_get_text_ranges`.
- Added batch Navigation tools: `navigation_to_reference_batch` and `navigation_find_references_batch`.
- Added IDE documentation lookup tools for VFS URL positions:
  `navigation_get_symbol_info` and `navigation_get_symbol_info_batch`.
- Added code quality toolset `CodeQualityMcpTools` with:
  `quality_get_file_problems`, `quality_get_scope_problems`, `quality_reformat_file`,
  `quality_optimize_imports_file`, `quality_reformat_scope_files`, and `quality_optimize_imports_scope_files`.
- Extended `CodeQualityMcpTools` with:
  `quality_get_scope_problems_by_severity`, `quality_fix_file_quick`, `quality_fix_scope_quick`,
  `quality_list_inspection_profiles`, `quality_code_cleanup_file`, and `quality_code_cleanup_scope_files`.
- Added first-call optimization shortcuts across toolsets:
  `scope_get_default_descriptor`, `scope_resolve_standard_descriptor`, `scope_catalog_find_by_intent`,
  `scope_normalize_program_descriptor`, `scope_find_source_file_by_class_name`,
  `scope_search_symbols_quick`, `scope_search_symbols_with_stage_progress`, `scope_search_symbols_healthcheck`,
  `navigation_get_symbol_info_by_offset`, `navigation_get_symbol_info_auto_position`,
  `navigation_get_symbol_info_quick`, and `vfs_read_api_signature`.
- Added broader preset-based quick shortcuts to reduce first-call scope assembly:
  `scope_search_files_quick`, `scope_search_text_quick`,
  `quality_get_scope_problems_quick`, `quality_get_scope_problems_by_severity_quick`,
  and `quality_fix_scope_quick_by_preset`.

### Changed

- `vfs_get_url_from_local_path` now performs an automatic refresh retry before failing when `refreshIfNeeded=false`.
- `vfs_read_file*` now supports `clampOutOfBounds` to optionally clamp invalid range inputs to file bounds.
- Best-effort navigation batch tools (`navigation_to_implementation`, `navigation_find_overrides`,
  `navigation_find_inheritors`, `navigation_get_callers`) now support `fallbackToReferencesWhenEmpty`, and
  return fallback diagnostics in `NavigationResults.diagnostics`.
- Navigation errors for `jar://` inputs now explicitly suggest using `vfs_read_file*` when PSI/doc navigation
  is unavailable.
- `scope_search_files` text modes now support ordered multi-keyword matching (either explicit `keywords` or
  whitespace-split `query`), aligning behavior with IDE-like file matcher semantics.
- `scope_search_files` now uses `FilenameIndex` for `NAME` mode on `GLOBAL` scopes, and falls back to
  `ProjectFileIndex` traversal for non-global or non-indexable scopes.
- `ScopeProgramOp` JSON decoding now accepts legacy token name `ATOM` as an alias of `PUSH_ATOM`, fixing
  backward compatibility for previously serialized scope programs.

[Unreleased]: https://github.com/ghostflyby/IntelliJ-Plugins/compare/WorkspaceMcpTools-v1.0.0...HEAD
[1.0.0]: https://github.com/ghostflyby/IntelliJ-Plugins/commits/WorkspaceMcpTools-v1.0.0
