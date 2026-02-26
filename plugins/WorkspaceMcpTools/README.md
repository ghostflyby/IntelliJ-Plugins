# Workspace MCP Tools

<!-- Plugin description -->
MCP toolset for IntelliJ workspace operations, including VFS, Document, and Symbol Navigation integrations.

VFS tools:

- `vfs_get_url_from_local_path`
- `vfs_get_local_path_from_url`
- `vfs_refresh`
- `vfs_exists`
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
- optional `clampOutOfBounds=true`: clamp invalid char/line ranges to file bounds instead of failing

VFS read tools only return persisted VFS content. If you need unsaved editor content, call `document_*` tools.
`vfs_list_files` returns an object wrapper with `names`.

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

Navigation tools:

- `navigation_to_reference`
- `navigation_to_type_definition`
- `navigation_to_implementation`
- `navigation_find_overrides`
- `navigation_find_inheritors`
- `navigation_find_references`
- `navigation_get_callers`

Navigation tools resolve source `(row, column)` to target file URI and target `(row, column)`.  
Both source and target line/column are 1-based.
Batch navigation tools return an object wrapper with `items`.
Best-effort batch tools also return `diagnostics` when fallback behavior is applied.

Some navigation tools are best-effort (notably caller/type/inheritor/override queries) and may produce false negatives
depending on language PSI shape. You can set `fallbackToReferencesWhenEmpty=true` to auto-fallback to
`navigation_find_references` semantics inside the same call.

SearchScope tools:

- `scope_list_catalog`
- `scope_validate_pattern`
- `scope_resolve_program`
- `scope_describe_program`
- `scope_contains_file`
- `scope_filter_files`
- `scope_search_files`
- `scope_find_files_by_name_keyword`
- `scope_find_files_by_path_keyword`
- `find_in_directory_using_glob`

`scope_contains_file` checks membership of one VFS file URL against a scope descriptor.
`scope_filter_files` filters multiple VFS file URLs and returns matched/excluded/missing lists.
`scope_search_files` supports `NAME` / `PATH` / `NAME_OR_PATH` / `GLOB` matching modes, with timeout and cancellable background progress.
For text modes, input is matched as ordered keywords (whitespace-split by default): every keyword must exist and appear in the same order.
`NAME` mode prefers `FilenameIndex` when scope resolves to `GLOBAL`; non-global scopes use traversal with `scope.contains(file)` filtering.
<!-- Plugin description end -->
