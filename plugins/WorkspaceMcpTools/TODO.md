# WorkspaceMcpTools TODO

Status: Planned
Last Updated: 2026-02-27

## Refactor Plan

1. Add a docs-generation workflow to keep tool inventories synchronized between code and docs.
2. Isolate scope-provider compatibility/reflection behavior behind a dedicated compatibility layer.
3. Add contract regression tests for quick presets, `@JsonNames` aliases, and descriptor compatibility.
4. Continue reducing duplicated validation/activity text by expanding shared common helpers.

## Done Criteria

1. Documentation drift is minimized by generation or automated checks.
2. Scope provider compatibility logic is localized and testable.
3. Serialization and descriptor backward-compatibility are covered by tests.

## Post-Implementation Archive

Move this file content into:
`plugins/WorkspaceMcpTools/docs/WorkspaceMcpTools-ContractAndDocsRefactor.md`
