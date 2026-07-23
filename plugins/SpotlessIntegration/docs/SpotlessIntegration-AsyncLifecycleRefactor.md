# SpotlessIntegration Async Lifecycle Refactor

Spotless daemon control has two internal owners:

- `SpotlessDaemonCoordinator` owns ordered provider discovery, stable IDs, dynamic extension sessions, provider-state
  collection, target selection, commands, and the combined status snapshot.
- `SpotlessDaemonRegistry` owns daemon executions, `Starting`/`Ready` transitions, readiness, transport invalidation,
  HTTP stop, cancellation, deadlines, and runtime snapshots.

## Provider Sessions And Selection

A `ProviderSession` contains the provider, its child scope, stable ID, and current immutable snapshot. The scope job is
the attachment state. Coordinator also keeps a unique ID-to-session index so commands and UI state do not retain backend
provider objects.

Provider IDs and targets follow IntelliJ EP order. Duplicate IDs keep the first contribution; removal promotes the next
one. Invalid target results are misses. Once the first valid target is selected, startup or runtime failure does not
fall back.

Dynamic provider removal removes the session from selection, cancels its state collector, synchronously releases its
Registry entries under one two-second deadline, publishes provider state, and then returns to the EP removal boundary.

## Daemon Execution

Registry keys are `(ProviderSession, normalizedRoot)`, so different providers may own daemons for the same root. Each
entry is either `Starting` or `Ready` and references one `DaemonExecution` with its own `SupervisorJob`, execution
scope, shared startup task, and `None`/`Active` handle ownership state.

The execution job is a child of the Registry `SupervisorJob`. Both the startup task and provider lifetime task are its
children, not children of the first request. Callers only await the shared startup task; canceling one caller does not
cancel daemon startup.

Startup is:

```text
provider startDaemon(context)
-> context launchHandle(endpoint, lifetime)
-> verify the returned handle is the execution's Active handle
-> race HTTP readiness (60 seconds) against lifetime completion
-> atomically publish Ready if the same execution and generation are current
```

`launchHandle` performs a short non-cancellable `None -> Active` ownership commit after cancellably acquiring the
Registry mutex. Its internal lifetime task starts atomically so cancellation immediately after handoff still enters the
lifetime block and runs `finally`. Duplicate or late launch, a foreign returned handle, provider failure, and lifetime
completion are rejected before Ready publication. Completion from an old execution conditionally detaches only an entry
still pointing to the same execution and handle.

## Bidirectional Termination

Provider-initiated termination is the handle lifetime job completing normally, exceptionally, or through provider-side
cancellation. Provider `finally` performs process-side cleanup; Registry removes the matching entry, invalidates
capability cache, and publishes runtime state without sending `/stop`. The public handle exposes only `Job`; Registry
keeps the backing `Deferred<Unit>` internally so a failure during startup is not replaced by execution-parent
cancellation. Coroutine stack-trace recovery may copy the throwable instance while retaining its type, message, and
original cause.

Core-initiated termination is:

```text
Mutex: detach entry -> invalidate cache -> publish snapshot
Outside Mutex: best-effort HTTP /stop when endpoint exists
-> cancel the execution job
-> join the execution job, covering provider finally and startup completion
```

Readiness failure, generation change, transport failure, manual stop/restart, provider removal, and project disposal all
use the core path. A `Starting` execution with an Active handle still receives `/stop`; one without a handle is canceled
directly. `/stop`-driven process exit and subsequent cancellation are idempotent because every completion handler checks
execution and handle identity. Replacement always creates a new immutable handle after the old execution cleanup path;
endpoints are never replaced inside a handle.

Normal release uses one five-second deadline. Dynamic provider removal uses one two-second deadline. HTTP stop,
cancellation, provider `finally`, and joins consume the same remaining time. Logical state stays detached after timeout;
provider cleanup running in `NonCancellable` may continue best-effort.

## Formatting And UI

Formatting follows `resolveTarget -> Coordinator.withDaemon -> steps/format`. No per-request health check is performed.
Only transport exceptions evict the exact Ready entry. HTTP response errors remain `SpotlessFormatResult.Error`;
startup, readiness, and transport failures propagate through the existing async error channel.

Coordinator status contains provider IDs, roots, and `Starting`/`Ready` state only. The frontend presentation EP
resolves display names dynamically with ID fallback. Widget activity remains the sole status-bar platform boundary and
is disabled in headless/unit-test environments.

Current packaging remains monolithic. Future split-mode ownership is backend for provider/runtime/Gradle integration,
frontend for presentation and UI, and shared for future internal status/command DTOs. No RPC or split-mode descriptor is
introduced by this refactor.
