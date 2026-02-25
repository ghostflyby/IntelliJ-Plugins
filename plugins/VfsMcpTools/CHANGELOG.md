<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# File MCP Tools Changelog

## [Unreleased]

### Added

- Initial plugin scaffold with MCP Server dependency and VFS tool placeholders.
- `vfs_read_file` now supports `FULL`, `CHAR_RANGE`, and `LINE_RANGE` read strategies.
- Added convenience tools: `vfs_read_file_full`, `vfs_read_file_by_char_range`, `vfs_read_file_by_line_range`.
- Merged Document MCP tools into the same plugin and registered as a second MCP toolset.
- Refactored Document MCP APIs to map directly to `Document` methods (get/set/insert/delete/replace and line/offset
  queries), all as `suspend`.
