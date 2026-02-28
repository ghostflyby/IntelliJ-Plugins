# LiveTemplatesWithSelection TODO

Status: Planned
Last Updated: 2026-02-27

## Refactor Plan

1. Replace plugin-global listener disposal with editor/document scoped disposables.
2. Ensure `UserDataHolder` keys are cleaned at editor disposal boundaries.
3. Harden template substitution edge cases: empty selection, multi-caret, and nested variable interactions.
4. Expand tests for listener attachment/detachment and selection replacement correctness.

## Done Criteria

1. Listener lifecycle is scoped to editor/document lifetime.
2. No stale user data remains after editor disposal.
3. Edge-case behavior is covered with tests.

## Post-Implementation Archive

Move this file content into:
`plugins/LiveTemplatesWithSelection/docs/LiveTemplatesWithSelection-LifecycleRefactor.md`
