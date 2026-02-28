# IdeaVimToggleIME TODO

Status: Planned
Last Updated: 2026-02-27

## Refactor Plan

1. Isolate unstable IdeaVim listener API usage behind a dedicated adapter layer.
2. Add explicit cleanup/unregistration flow to support dynamic plugin unload safety.
3. Add tests for mode-transition IME behavior and editor focus lifecycle.
4. Document rationale for unstable API usage directly in code comments.

## Done Criteria

1. Unstable API usage is localized and documented.
2. Listener lifecycle is unload-safe.
3. Behavior remains stable across mode/focus transitions.

## Post-Implementation Archive

Move this file content into:
`plugins/IdeaVimToggleIME/docs/IdeaVimToggleIME-ListenerLifecycleRefactor.md`
