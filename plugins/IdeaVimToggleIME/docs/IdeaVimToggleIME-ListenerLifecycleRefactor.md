# IdeaVimToggleIME Listener Lifecycle Refactor

Status: Completed

Completed: 2026-03-01

## Goals

- Isolate unstable IdeaVim listener API usage behind a dedicated adapter layer.
- Add explicit cleanup/unregistration flow for dynamic plugin unload safety.
- Add tests for mode-transition IME behavior and editor focus lifecycle.
- Document rationale for unstable API usage directly in code comments.

## Implementation Notes

- Listener registration is centralized in `IdeaVimListenerAdapter` with a rationale comment.
- Registration lifetime is owned by `ImeListenerService` and cleaned up on disposal.
- IME state updates are routed through `ImeStateController`.

## Tests Added

- `ImeStateControllerTest` covers mode-transition IME toggling and focus lifecycle updates.
