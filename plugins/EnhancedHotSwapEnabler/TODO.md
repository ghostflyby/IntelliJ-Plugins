# EnhancedHotSwapEnabler TODO

Status: In Progress
Last Updated: 2026-02-27

## Implemented In This Iteration

1. Switched agent acquisition to a non-blocking cache-first flow with async warm-up download.
2. Removed blocking agent lookup from run configuration and Gradle task patching paths.
3. Replaced broad dispose-time project scan with weak-tracked holder cleanup in run configuration extension.
4. Added inheritance resolution unit tests for config fallback ordering.

## Refactor Plan

1. [x] Replace blocking agent download flow with suspend-first orchestration.
2. [x] Remove `runBlocking`-based calls from run/gradle patching paths and keep UI-thread-safe behavior.
3. [x] Improve run configuration user-data lifecycle cleanup with explicit unload-safe disposal boundaries.
4. [~] Add focused tests for agent availability fallback and configuration inheritance behavior.
   Inheritance tests are done; agent warm-up fallback tests are still pending.

## Next Slice

1. Add tests for warm-up behavior when agent is missing and when download fails.
2. Verify run/Gradle paths continue without agent injection before cache is ready.
3. Finalize and archive into docs after test coverage is complete.

## Done Criteria

1. No blocking download path in patcher execution.
2. Agent download failures degrade gracefully without breaking launch.
3. Coverage exists for inheritance and fallback behavior.

## Post-Implementation Archive

Move this file content into:
`plugins/EnhancedHotSwapEnabler/docs/EnhancedHotSwapEnabler-AgentLifecycleRefactor.md`
