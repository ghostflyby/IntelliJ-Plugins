<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Workspace MCP Tools Changelog

## [Unreleased]

### Added

- Initial plugin scaffold with MCP Server dependency and VFS tool placeholders.
- `vfs_read_file` now supports `FULL`, `CHAR_RANGE`, and `LINE_RANGE` read strategies.
- Added convenience tools: `vfs_read_file_full`, `vfs_read_file_by_char_range`, `vfs_read_file_by_line_range`.
- Merged Document MCP tools into the same plugin and registered as a second MCP toolset.
- Refactored Document MCP APIs to map directly to `Document` methods (get/set/insert/delete/replace and line/offset
  queries), all as `suspend`.
- Added `scope_list` toolset entry to list project/module/directory/user-defined/predefined/Git-tracked scopes.
- Added `navigation_to_reference` tool to resolve reference target file and 1-based row/column.
- Renamed navigation toolset implementation class to `SymbolNavigationMcpTools`.
- Added navigation tools: `navigation_to_type_definition`, `navigation_to_implementation`,
  `navigation_find_overrides`, `navigation_find_inheritors`, `navigation_find_references`, and
  `navigation_get_callers`.
- Replaced generic MCP boundary list returns with serializable wrapper DTOs (`VfsFileNamesResult`,
  `NavigationResults`) for stronger boundary typing.
