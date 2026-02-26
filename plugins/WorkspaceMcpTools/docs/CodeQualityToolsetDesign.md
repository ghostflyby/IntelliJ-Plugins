# Code Quality Toolset Design (Phase 1)

## Goals
- Add MCP tools for code-quality workflows using IDE-native behavior.
- Keep inputs centered on `vfs url` and optionally `scope` descriptors.
- Provide batch/scope variants with progress, timeout, truncation, and diagnostics.

## Research Inputs
### Local source (primary)
- Official MCP toolset source from IDE distribution:
  - `AnalysisToolset.kt` (`get_file_problems` implementation style)
  - `FormattingToolset.kt` (`ReformatCodeProcessor` execution style)
  - `FileToolset.kt` (timeout/progress/reporting patterns)
- IntelliJ platform source:
  - `ReformatCodeProcessor.java`
  - `OptimizeImportsProcessor.java`
  - `AbstractLayoutCodeProcessor.java`

### Web references (supplement)
- JetBrains inspection workflow examples and plugin-side inspection integration patterns.
- IntelliJ API reference pages for search/inspection-related helper classes.

## Implementation Choices
### 1) Problems collection
- Chosen path:
  - `DaemonCodeAnalyzerImpl.runMainPasses(...)` + `HighlightingSessionImpl` for file-level diagnostics.
- Reason:
  - Closest to editor highlighting behavior users see in IDE.
  - Matches official MCP `AnalysisToolset` behavior.
- Tradeoff:
  - Relies on unstable API usage; explicitly suppressed with file-level suppression and documented as compatibility risk.

### 2) Reformat / optimize imports
- Chosen path:
  - `ReformatCodeProcessor` and `OptimizeImportsProcessor`.
- Reason:
  - Executes IDE’s built-in code style/import pipelines.
  - Preserves command/write semantics consistent with IDE actions.
- Execution model:
  - Trigger on EDT, wait on post-runnable completion.

### 3) Scope-limited execution
- Chosen path:
  - Resolve `ScopeProgramDescriptorDto` via existing `ScopeResolverService`.
  - Iterate project content and filter by `scope.contains(file)`.
- Reason:
  - Reuses existing scope abstraction and keeps behavior consistent with current scope file search fallback path.
- Tradeoff:
  - Scope operations currently process project-content files only (external library files skipped).

## Added Tools
- `quality_get_file_problems`
- `quality_get_scope_problems`
- `quality_reformat_file`
- `quality_optimize_imports_file`
- `quality_reformat_scope_files`
- `quality_optimize_imports_scope_files`

## Result Contract Design
- Single-file outputs include:
  - `fileUrl`, optional `filePath`, operation/result flags, timeout marker.
- Scope outputs include:
  - `scanned/processed/success/failure/skipped` counters
  - `probablyHasMoreMatchingFiles` and `timedOut`
  - `diagnostics` for truncation/timeouts/scope behavior hints.

## Follow-up Candidates
- `quality_fix_file_quick` (optimize imports + reformat pipeline in one call)
- `quality_fix_scope_quick` (scope-level combined pipeline)
- `quality_get_scope_problems_by_severity` (server-side severity aggregation and filtering)
- Optional inspection-profile selection support for project-specific rulesets.
