# GradleMcpTools TODO

Status: Planned
Last Updated: 2026-02-27

## Refactor Plan

1. Split the monolithic toolset file into focused modules: listing, execution, sync, and cancellation.
2. Extract shared path matching and cancellation/retry logic into internal helpers.
3. Add activity reporting for long-running operations to improve MCP runtime observability.
4. Add contract-safe regression tests for DTO/schema evolution and timeout/cancel semantics.

## Done Criteria

1. Main tool entry is thin and delegating.
2. Shared logic is centralized and reused.
3. Tool activity is reported for long operations.
4. Backward-compatible DTO behavior is validated.

## Post-Implementation Archive

Move this file content into:
`plugins/GradleMcpTools/docs/GradleMcpTools-ExecutionAndContractRefactor.md`
