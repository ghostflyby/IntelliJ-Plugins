# Mill Configurable ViewModel: Observable Bindings and Tests

Status: Implemented
Completed: 2026-11-24

## Why the ViewModel Battles the Settings DSL

The settings UI is built with the Kotlin UI DSL and binds Swing cells directly to
`ObservableProperty` instances (`bindText`, `bindRightHint`, `bindTrailingHint`, `bindItem`,
`bindSelected`). The ViewModel, however, is a set of derived `StateFlow`s (combine,
transformLatest, debounced probes). Two adapters connect the two worlds, and both had a subtle
defect before this change.

## `toObservable` / `toMutableObservable` / `toReducerObservable`

Each adapter wraps one flow in the platform property interface. `get()` returns the flow's
current value, `set()` updates the underlying flow or runs the ViewModel reducer, and
`afterChange` forwards to one `FlowChangeListeners` per property
(`MillConfigurableViewModel.kt`).

`FlowChangeListeners` is deliberately not "one collector per listener":

1. **One collector per property.** A per-listener collector resumes each listener on its own
   coroutine, so the delivery order of two listeners that registered for the same change is
   undefined. A view binding could run after a listener registered later, so an observer could
   see the property's new value before the widget did. The single collector delivers changes to
   listeners in registration order, on `Dispatchers.UI`.
2. **Per-listener last value instead of positional dropping.** The earlier adapter dropped the
   first collected value with `drop(1)` to skip the flow's replay. A change that landed between
   a listener's registration and its first delivery was then lost for that listener, leaving the
   widget stale until the next change. Each entry now carries its own `last` value: it receives
   every value different from what it already knows about, including the value present at
   registration time if it changes before the first delivery.
3. **Registration ordered against delivery, from the delivered value.** A StateFlow only hands a
   collector values that differ from the last one it handed *that collector*, so a property that
   moves away and back (a transient `""` between two identical version texts) is never delivered
   again. The adapter therefore publishes each value to `current` and marks the receiving entries
   under the same lock that `register` takes: a listener is either registered before a delivery
   pass (and receives the value) or after it (and starts from `current`, which `get()` also
   returns). A listener can never end up waiting for a notification that already happened.

Delivery is conflated by `StateFlow`: listeners see values, not every intermediate state. Code
that must observe an intermediate value (for synchronization) has to make it distinguishable
rather than assume it will be observed.

## Test Seam: `committedProbesRunning`

A committed probe that finishes can change no visible property: a draft preview may win, or its
result may repeat the previous one. Tests therefore cannot await its completion through visible
properties. `committedProbesRunning` exposes the in-flight set of the committed-probe reducer
directly and is used as an explicit fence in tests that must know the committed result has been
reduced.

## Test Patterns (`MillConfigurableViewModelTest`)

- `@TestApplication`, class-level `@Timeout(10)`, `projectFixture` and `disposableFixture`.
  Every model is created against a dedicated `modelParent` disposable that `@AfterEach` disposes
  before the project fixture tears down, so model coroutines and probes stop first.
- Test methods are `suspend` functions; the platform JUnit5 framework supports them. No
  `runBlocking`, no `KotlinCoroutineExtension`.
- `onUi` runs Swing work as `CoroutineScope(Dispatchers.UI).async { }.await()`. It deliberately
  starts a root coroutine with only the dispatcher: inheriting the test's context installed the
  project's thread context on the UI thread for the duration and made the platform's
  project-leak check fail intermittently.
- `nextValue` / `nextValueWhere` register `afterChange` **before** the action that causes the
  change, then complete a `CompletableDeferred`. If the value is already present, the wait
  completes immediately. Because listeners run in registration order, a deferred completing for
  a value has already let earlier-registered bindings run.
- Widget assertions read the widget inside the notification of the expected value when later
  probe restarts could legitimately blank the value before a post-await read. A wait must also
  be satisfiable only by the intended producer: the draft probe in the `right hint` test reports
  a version distinct from the committed probe's, because a shared version completed the wait
  immediately from the already-present committed result and let the assertion race the draft
  preview (the transient empty hint while a new probe runs).
- Model-value waits use the ViewModel's own flow (`executableVersionTextValues`) rather than the
  observable property. Property notifications are delivered on the UI thread; a headless run
  must not depend on how quickly that queue is serviced (a busy or stalled EDT turned a model
  transition into a test timeout). Widgets are still asserted through the property notification,
  where the EDT hop is the point.
- No `waitUntil`-style polling: the tests suspend on notifications.

## Probe Restarts and the Transient Empty Hint

`executablePreviewStateFlow` combines four derived flows; a change to any input (text, selected
project, choices, selected choice) produces a new preview state. `draftProbeResultFlow`
(`transformLatest`) immediately emits `null` for a new preview and then the probe result, so the
version hint is empty while a new draft probe is in flight. That is intentional ("unknown until
probed"), and it is why tests synchronize on notifications instead of watching for a value to
stay put.
