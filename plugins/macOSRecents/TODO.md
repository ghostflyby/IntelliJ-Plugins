# macOSRecents TODO

Status: Planned
Last Updated: 2026-02-27

## Refactor Plan

1. Replace full reset-and-rebuild recents flow with diff-based updates.
2. Add debounce/throttling to reduce repeated Cocoa bridge operations during burst events.
3. Encapsulate Foundation bridge calls behind a dedicated service layer with explicit error isolation.
4. Add tests for ordering, deduplication, and startup/update behavior.

## Done Criteria

1. Recents updates are incremental.
2. Event bursts do not produce repeated heavy bridge calls.
3. Failures are isolated and do not break project startup flow.

## Post-Implementation Archive

Move this file content into:
`plugins/macOSRecents/docs/macOSRecents-CocoaBridgeRefactor.md`
