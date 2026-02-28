# SpotlessIntegration TODO

Status: Planned
Last Updated: 2026-02-27

## Refactor Plan

1. Remove production `runBlocking` usage and migrate to coroutine-native async flows.
2. Replace unfinished `TODO()` production stubs with implemented behavior or remove dead extension wiring.
3. Rework daemon lifecycle shutdown to avoid blocking dispose paths.
4. Revisit API surface visibility (`public` vs `internal`) and keep ABI updates explicit.
5. Add integration tests for format/canFormat behavior, daemon startup health checks, and failure handling.

## Done Criteria

1. No blocking coroutine bridges in production formatting path.
2. No unresolved `TODO()` in active production code.
3. Daemon lifecycle is unload-safe and non-blocking.
4. ABI and visibility changes are intentional and documented.

## Post-Implementation Archive

Move this file content into:
`plugins/SpotlessIntegration/docs/SpotlessIntegration-AsyncLifecycleRefactor.md`
