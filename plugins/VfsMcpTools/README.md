# File MCP Tools

<!-- Plugin description -->
MCP toolset for IntelliJ file operations, including VFS and Document integrations.

VFS tools:

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

Document tools:

- `document_get_text`
- `document_get_chars_sequence`
- `document_get_immutable_char_sequence`
- `document_get_text_range`
- `document_get_text_length`
- `document_get_line_count`
- `document_get_line_number`
- `document_get_line_start_offset`
- `document_get_line_end_offset`
- `document_get_line_separator_length`
- `document_is_line_modified`
- `document_is_writable`
- `document_get_modification_stamp`
- `document_insert_string`
- `document_delete_string`
- `document_replace_string`
- `document_set_text`

These methods are designed to map directly to `com.intellij.openapi.editor.Document` APIs.
<!-- Plugin description end -->
