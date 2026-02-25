# VFS MCP Tools

<!-- Plugin description -->
MCP toolset for IntelliJ VFS integration.

Current tools:

- `vfs_get_url_from_local_path`
- `vfs_get_local_path_from_url`
- `vfs_refresh`
- `vfs_file_stat`
- `vfs_list_files`
- `vfs_read_file`
- `vfs_read_file_full`
- `vfs_read_file_by_char_range`
- `vfs_read_file_by_line_range`

`vfs_read_file` supports multiple read strategies via `mode`:

- `FULL`: read the whole file
- `CHAR_RANGE`: read `[startChar, endCharExclusive)`
- `LINE_RANGE`: read `[startLine, endLineInclusive]` (1-based)

`includeUnsavedDocument=true` reads from open editor document when available, so unsaved changes are visible.
<!-- Plugin description end -->
