# macOSRecents Cocoa Bridge Refactor

## Completed Scope

1. Replaced full reset-and-rebuild recents flow with diff-based synchronization.
2. Added debounced scheduling to reduce repeated bridge operations during burst events.
3. Encapsulated Foundation bridge calls in a dedicated service layer with explicit error isolation.
4. Added unit tests for ordering, deduplication, startup merge behavior, and update behavior.
5. Added scheduler-focused tests that validate burst coalescing and startup path retention.
6. Switched the scheduler to a true last-event debounce and added debug-level sync decision telemetry.

## Outcome

1. Recents updates are now incremental when possible.
2. Burst events now wait for the latest snapshot before bridge execution.
3. Bridge failures are isolated and no longer break startup or update flow.
4. Debug logs can now distinguish skip, append, and rebuild sync paths during troubleshooting.
