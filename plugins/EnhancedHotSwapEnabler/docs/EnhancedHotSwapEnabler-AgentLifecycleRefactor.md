# EnhancedHotSwapEnabler Agent Lifecycle Refactor

Status: Completed  
Last Updated: 2026-02-27

## Scope

1. Replace blocking HotSwapAgent acquisition with cache-first non-blocking warm-up flow.
2. Keep run configuration and Gradle task execution paths resilient when agent is not yet cached.
3. Tighten run configuration user-data lifecycle cleanup for plugin unload safety.
4. Add focused tests for configuration inheritance and agent warm-up fallback behavior.

## Implemented

1. Switched agent acquisition to cache-first lookup with asynchronous warm-up download.
2. Removed blocking agent lookup from run configuration patching and Gradle task patching paths.
3. Replaced broad dispose-time project scan with weak-tracked holder cleanup in run configuration extension.
4. Extracted shared agent-jar resolution helper used by both run and Gradle paths.
5. Added inheritance resolution unit tests for config fallback ordering.
6. Added agent resolver tests for disabled/no-cache/cached-path scenarios.
7. Added agent manager tests for:
   - cached jar short-circuit behavior
   - missing-cache warm-up behavior
   - graceful download-failure fallback

## Outcome Against Done Criteria

1. No blocking download path remains in patcher execution paths.
2. Agent download failures degrade gracefully and keep launch flow non-blocking.
3. Coverage exists for inheritance ordering and agent fallback behavior.

## Traceability

Source TODO was moved from:
`plugins/EnhancedHotSwapEnabler/TODO.md`
