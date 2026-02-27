# EnhancedHotSwapEnabler TODO

Status: Planned
Last Updated: 2026-02-27

## Refactor Plan

1. Replace blocking agent download flow with suspend-first orchestration.
2. Remove `runBlocking`-based calls from run/gradle patching paths and keep UI-thread-safe behavior.
3. Improve run configuration user-data lifecycle cleanup with explicit unload-safe disposal boundaries.
4. Add focused tests for agent availability fallback and configuration inheritance behavior.

## Done Criteria

1. No blocking download path in patcher execution.
2. Agent download failures degrade gracefully without breaking launch.
3. Coverage exists for inheritance and fallback behavior.

## Post-Implementation Archive

Move this file content into:
`plugins/EnhancedHotSwapEnabler/docs/EnhancedHotSwapEnabler-AgentLifecycleRefactor.md`
