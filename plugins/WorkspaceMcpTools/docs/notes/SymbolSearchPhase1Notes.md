# Symbol Search Phase 1 Notes

> 状态：部分实现（含待办项）
> 最后核对：2026-02-26

## Tool Usage Record
- Scope bootstrap:
  - Used `scope_list_catalog` + `scope_resolve_program` to build a stable `All Places` descriptor and validate cross-project/library search inputs.
- Library source lookup:
  - Used `scope_find_files_by_name_keyword` to locate IntelliJ source JAR classes (`GotoSymbolModel2`, `FindSymbolParameters`, `ChooseByNameInScopeItemProvider`, `GlobalSearchScopeUtil`, `GlobalAndLocalUnionScope`, etc.).
  - Then used `vfs_read_file_by_line_range` for targeted API signature verification.
- Project source integration:
  - Used `get_file_text_by_path` and `list_directory_tree` to align new tool with existing scope/file/text MCP tool style.

## Observed Tooling Limitations / Bugs
- `scope_list_catalog` still shows provider reflection access failure in diagnostics:
  - `PluginDescriptorDomFileSearchScopeProvider` invocation fails due accessibility mismatch.
  - Impact: catalog completeness degrades in some IDE/plugin combinations.
- Very large catalog responses are easy to exceed transport output limits:
  - Caller side should prefer `scopeRefId` filtering or scoped follow-up calls instead of consuming full catalog payload each turn.
- For library API research, direct `vfs_read_file*` on located JAR entry is significantly more reliable than context-sensitive navigation tools.

## Common MCP Calling Patterns
- Recommended symbol-search research chain:
  1. `scope_list_catalog` (discover scope)
  2. `scope_resolve_program` (compile descriptor)
  3. `scope_find_files_by_name_keyword` (locate target class in project/library)
  4. `vfs_read_file_by_line_range` (read minimal signature windows)
- For JAR-heavy work:
  - Use `scope_find_files_by_name_keyword` first, avoid path guessing for `jar://` URLs.
  - Prefer line-range reads over full file reads to reduce payload.
- For implementation debugging:
  - Keep `scope` descriptors explicit in every call to avoid accidental fallback to project-only semantics.

## Potential Performance Optimizations
- Symbol recall path:
  - Keep `ChooseByNameInScopeItemProvider` as primary path; avoid fallback traversal unless provider capability is missing.
- Conversion phase:
  - Batch PSI/document extraction in fewer read actions for large candidate sets.
  - Early-stop conversion once enough post-filtered items are collected (currently can still convert all recalled candidates).
- Post-filter path:
  - Cache reflection result for `GlobalAndLocalUnionScope` field access in-memory per call.
  - Avoid duplicate `contains/isInScope` checks by reusing precomputed file-level decisions.
- Fallback path:
  - Current model fallback enumerates names first; can add pattern-prefiltered contributor fallback to reduce name explosion.

## Candidate Shortcut Tool Entrypoints
- `scope_search_symbols_by_kind`:
  - Inputs: `query`, `kind`, `scope`, `maxResultCount`.
  - Behavior: wraps `scope_search_symbols` and applies kind filter server-side.
- `scope_search_symbols_in_directory`:
  - Inputs: `query`, `directoryUrl`, `scope`, `maxResultCount`.
  - Behavior: combines directory atom with descriptor and then runs symbol search.
- `scope_search_symbols_batch`:
  - Inputs: list of `{query, scope}` pairs, shared timeout policy.
  - Behavior: batch execution with per-item diagnostics.
- `scope_search_symbols_by_language`:
  - Inputs: `query`, `languageIds`, `scope`.
  - Behavior: shortcut filter on returned `language`.
